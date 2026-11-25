//! Explicitly requested, bounded Responses tool-roundtrip verification.
use std::borrow::Cow;
use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use codex_api::{Compression, ResponseEvent, ResponseStream, ResponsesClient};
use codex_http_client::HttpClientFactory;
use codex_model_provider_info::ModelProviderInfo;
use codex_protocol::error::{CodexErr, Result};
use codex_protocol::openai_models::{ConfigShellToolType, InputModality, ModelInfo, ModelVisibility};
use serde_json::{Value, json};

type VerifiedModels = HashSet<(String, String)>;

fn verified_models() -> &'static Mutex<VerifiedModels> {
    static VERIFIED: OnceLock<Mutex<VerifiedModels>> = OnceLock::new();
    VERIFIED.get_or_init(|| Mutex::new(HashSet::new()))
}

/// A catalog originator identifies a client surface, not an authenticated principal.
pub(crate) fn probe_identity(info: &ModelProviderInfo) -> Result<String> {
    let mut scoped = info.clone();
    if let Some(headers) = &mut scoped.http_headers {
        headers.retain(|name, _| !name.eq_ignore_ascii_case("originator"));
    }
    if let Some(headers) = &mut scoped.env_http_headers {
        headers.retain(|name, _| !name.eq_ignore_ascii_case("originator"));
    }
    crate::models_identity::identity(&scoped, None)
}

fn failure(message: &str) -> CodexErr {
    CodexErr::InvalidRequest(message.to_owned())
}

/// Probe results are bound to the credential/endpoint digest and live only for this core process.
/// They never authorize hosted tools, image input, extra reasoning levels or parallel agents.
pub(crate) fn refine_catalog<'a>(identity: Option<&str>, models: &'a [ModelInfo]) -> Cow<'a, [ModelInfo]> {
    let Some(identity) = identity else { return Cow::Borrowed(models); };
    let Ok(verified) = verified_models().lock() else { return Cow::Borrowed(models); };
    if !models.iter().any(|m| verified.contains(&(identity.to_owned(), m.slug.clone()))) {
        return Cow::Borrowed(models);
    }
    let mut models = models.to_vec();
    for model in &mut models {
        if !model.supported_in_api || !verified.contains(&(identity.to_owned(), model.slug.clone())) {
            continue;
        }
        if model.visibility == ModelVisibility::Hide {
            model.visibility = ModelVisibility::List;
            model.shell_type = ConfigShellToolType::UnifiedExec;
            let budget = model.context_window.unwrap_or(8192).clamp(1, 8192);
            model.context_window = Some(budget);
            model.max_context_window = Some(budget);
            model.auto_compact_token_limit = None;
            model.input_modalities = vec![InputModality::Text];
            model.apply_patch_tool_type = None;
            model.supported_reasoning_levels.clear();
            model.default_reasoning_level = None;
        }
        model.description = Some("已通过本次内核的 Responses 双轮工具回传测试；完整任务能力仍需验收".into());
    }
    Cow::Owned(models)
}

#[derive(Default)]
struct Round {
    items: Vec<Value>,
    model_confirmed: bool,
    model_mismatch: bool,
    text_delta: bool,
    usage_reported: bool,
}

async fn collect(mut stream: ResponseStream, model: &str) -> Result<Round> {
    let mut round = Round::default();
    let mut events = 0usize;
    while let Some(event) = stream.rx_event.recv().await {
        events += 1;
        if events > 4096 {
            return Err(failure("验证响应超出事件限制"));
        }
        match event.map_err(|_| failure("验证请求的响应流失败，请检查账号授权和模型协议"))? {
            ResponseEvent::ServerModel(actual) => {
                round.model_confirmed |= actual == model;
                round.model_mismatch |= actual != model;
            }
            ResponseEvent::OutputTextDelta(text) => round.text_delta |= !text.is_empty(),
            ResponseEvent::OutputItemDone(item) => {
                let value = serde_json::to_value(item).map_err(|_| failure("验证响应无法解析"))?;
                if round.items.len() >= 32 || value.to_string().len() > 65536 {
                    return Err(failure("验证响应超出内容限制"));
                }
                round.items.push(value);
            }
            ResponseEvent::Completed { token_usage, .. } => {
                round.usage_reported = token_usage.is_some();
                return Ok(round);
            }
            _ => {}
        }
    }
    Err(failure("验证响应未正常结束"))
}

fn first_call<'a>(items: &'a [Value], challenge: &str) -> Result<&'a str> {
    let mut calls = items.iter().filter(|item| item["type"] == "function_call");
    let call = calls.next().ok_or_else(|| failure("模型没有发起测试函数调用"))?;
    let arguments: Value = serde_json::from_str(call["arguments"].as_str().unwrap_or_default())
        .map_err(|_| failure("模型返回的函数参数无效"))?;
    if calls.next().is_some() || call["name"] != "codex_probe_echo" || arguments != json!({"value": challenge}) {
        return Err(failure("模型没有按约定发起唯一测试函数调用"));
    }
    call["call_id"].as_str().filter(|id| !id.is_empty())
        .ok_or_else(|| failure("模型未返回工具调用 ID"))
}

fn accepts_result(items: &[Value], challenge: &str) -> bool {
    !items.iter().any(|item| item["type"] == "function_call")
        && items.iter().filter(|item| item["type"] == "message" && item["role"] == "assistant")
            .filter_map(|item| item["content"].as_array())
            .flatten()
            .filter(|part| part["type"] == "output_text")
            .filter_map(|part| part["text"].as_str())
            .collect::<Vec<_>>().join("").trim() == challenge
}

pub struct DomesticProbeResult {
    pub model_identity_confirmed: bool,
    pub usage_reported: bool,
}

/// Only the approved domestic endpoints with explicit bearer credentials may run this probe.
/// No shell, files, network tools, user prompts or conversation history are attached.
pub async fn verify_domestic_model(
    info: ModelProviderInfo,
    model: &str,
    challenge: &str,
    result_challenge: &str,
    factory: HttpClientFactory,
) -> Result<DomesticProbeResult> {
    if model.len() > 256 || model.trim().is_empty()
        || !crate::is_domestic_live_catalog(info.base_url.as_deref())
        || info.model_catalog_url.is_some() || info.requires_openai_auth
        || info.auth.is_some() || info.gateway_oauth.is_some()
        || (info.env_key.is_none() && info.experimental_bearer_token.is_none())
    {
        return Err(failure("仅支持使用独立 API Key 的国内实时目录服务商"));
    }
    let identity = probe_identity(&info)
        .map_err(|_| failure("服务商凭据不可用，请重新授权"))?;
    // A failed re-verification must not retain an earlier admission.
    verified_models().lock().map_err(|_| failure("验证状态不可用"))?
        .remove(&(identity.clone(), model.to_owned()));
    let mut info = info;
    info.request_max_retries = Some(0);
    info.stream_max_retries = Some(0);
    let provider = crate::create_model_provider(info, None);
    let outcome = tokio::time::timeout(Duration::from_secs(45), async {
        let resolved = crate::resolve_request(provider.as_ref(), None, None, factory).await
            .map_err(|_| failure("无法解析独立服务商授权"))?;
        let transport = resolved.http_transport("responses").map_err(|_| failure("无法连接模型服务商"))?;
        let mut api_provider = resolved.api_provider;
        api_provider.retry.retry_429 = false;
        api_provider.retry.retry_5xx = false;
        api_provider.retry.retry_transport = false;
        let client = ResponsesClient::new(transport, api_provider, resolved.api_auth).with_body_model_reporting();
        let tools = json!([{"type":"function","name":"codex_probe_echo",
            "description":"Return a synthetic verification token; no external action.",
            "parameters":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}}]);
        let mut input = vec![json!({"type":"message","role":"user","content":[{"type":"input_text",
            "text":format!("Call codex_probe_echo exactly once with value {challenge}. After receiving its result, output exactly the returned text, with no extra characters.")}]})];
        let first = client.stream(json!({"model":model,"input":input,"tools":tools,"stream":true,"store":false,"max_output_tokens":1024}),
            http::HeaderMap::new(), Compression::None, None).await
            .map_err(|_| failure("第一轮 Responses 请求失败"))?;
        let first = collect(first, model).await?;
        let call_id = first_call(&first.items, challenge)?.to_owned();
        input.extend(first.items);
        input.push(json!({"type":"function_call_output","call_id":call_id,"output":result_challenge}));
        let second = client.stream(json!({"model":model,"input":input,"tools":tools,"stream":true,"store":false,"max_output_tokens":1024}),
            http::HeaderMap::new(), Compression::None, None).await
            .map_err(|_| failure("第二轮工具结果回传请求失败"))?;
        let second = collect(second, model).await?;
        if !second.text_delta || !accepts_result(&second.items, result_challenge) {
            return Err(failure("模型未通过流式工具结果回传验证"));
        }
        Ok(DomesticProbeResult {
            model_identity_confirmed: first.model_confirmed && second.model_confirmed
                && !first.model_mismatch && !second.model_mismatch,
            usage_reported: first.usage_reported && second.usage_reported,
        })
    }).await.map_err(|_| failure("模型验证超时，未取得完整双轮结果"))??;
    if outcome.model_identity_confirmed {
        verified_models().lock().map_err(|_| failure("验证状态不可用"))?
            .insert((identity, model.to_owned()));
    }
    Ok(outcome)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_checks_exact_call_and_exact_tool_result() {
        let items = vec![json!({"type":"function_call","name":"codex_probe_echo","arguments":"{\"value\":\"nonce\"}","call_id":"call_1"})];
        assert_eq!(first_call(&items, "nonce").unwrap(), "call_1");
        assert!(first_call(&items, "wrong").is_err());
        let mut duplicate = items.clone();
        duplicate.extend(items.clone());
        assert!(first_call(&duplicate, "nonce").is_err());
        let response = vec![json!({"type":"message","role":"assistant","content":[{"type":"output_text","text":"result"}]})];
        assert!(accepts_result(&response, "result"));
        assert!(!accepts_result(&response, "nonce"));
        let mut extra = response;
        extra.extend(items);
        assert!(!accepts_result(&extra, "result"));
    }

    #[tokio::test]
    async fn completed_stream_collects_model_and_output_delta() {
        let (tx, rx) = tokio::sync::mpsc::channel(8);
        tx.send(Ok(ResponseEvent::ServerModel("probe".into()))).await.unwrap();
        tx.send(Ok(ResponseEvent::OutputTextDelta("verified".into()))).await.unwrap();
        tx.send(Ok(ResponseEvent::Completed {
            response_id: "test".into(), token_usage: None, usage_metadata: None,
            reasoning_removals: None, end_turn: Some(true),
        })).await.unwrap();
        drop(tx);
        let stream = ResponseStream { routing_affinity: None, rx_event: rx, upstream_request_id: None, interrupt: None };
        let round = collect(stream, "probe").await.unwrap();
        assert!(round.model_confirmed && round.text_delta && !round.model_mismatch);
        assert!(!round.usage_reported);
    }

    #[test]
    fn probe_admission_is_scoped_and_conservative() {
        let mut model = codex_models_manager::model_info::model_info_from_slug("probe-fixture");
        model.visibility = ModelVisibility::Hide;
        let models = vec![model];
        verified_models().lock().unwrap().insert(("account-a".into(), "probe-fixture".into()));
        assert_eq!(refine_catalog(Some("account-b"), &models)[0].visibility, ModelVisibility::Hide);
        let admitted = refine_catalog(Some("account-a"), &models);
        assert_eq!(admitted[0].visibility, ModelVisibility::List);
        assert_eq!(admitted[0].context_window, Some(8192));
        assert_eq!(admitted[0].input_modalities, vec![InputModality::Text]);
        assert!(admitted[0].supported_reasoning_levels.is_empty());
    }
}

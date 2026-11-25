//! Mainland provider registry. Public model IDs are not Codex model metadata.
use codex_models_manager::model_info::model_info_from_slug;
use codex_protocol::error::CodexErr;
use codex_protocol::openai_models::{ApplyPatchToolType, ConfigShellToolType, InputModality, ModelInfo, ModelVisibility, ReasoningEffort, ReasoningEffortPreset};
use serde::Deserialize;
use std::collections::HashSet;
use std::sync::OnceLock;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DomesticProfile {
    pub(crate) base_url: String,
    pub(crate) catalog: String,
    models: Vec<ProfileModel>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProfileModel {
    id: String,
    context: i64,
    #[serde(default)]
    efforts: Vec<ReasoningEffort>,
    default_effort: Option<ReasoningEffort>,
    #[serde(default)]
    image: bool,
    #[serde(default)]
    apply_patch: bool,
}

fn profiles() -> &'static [DomesticProfile] {
    static PROFILES: OnceLock<Vec<DomesticProfile>> = OnceLock::new();
    PROFILES.get_or_init(|| serde_json::from_str(include_str!("../catalogs/domestic-providers.json"))
        .expect("bundled domestic provider registry must be valid"))
}

pub(crate) fn profile(base_url: Option<&str>) -> Option<&'static DomesticProfile> {
    let base = base_url?.trim_end_matches('/');
    profiles().iter().find(|p| p.base_url == base
        || (p.base_url == "https://dashscope.aliyuncs.com/compatible-mode/v1"
            && codex_model_provider_info::domestic_qwen_workspace(base).is_some()))
}

#[derive(Deserialize)]
struct PublicCatalog {
    data: Vec<PublicModel>,
}

#[derive(Deserialize)]
struct PublicModel {
    id: String,
    context_window: Option<i64>,
    input_modalities: Option<Vec<String>>,
    effort: Option<PublicEffort>,
}

#[derive(Deserialize)]
struct PublicEffort {
    supported_levels: Vec<ReasoningEffort>,
    default_level: ReasoningEffort,
}

pub fn is_domestic_live_catalog(base_url: Option<&str>) -> bool {
    base_url.and_then(codex_model_provider_info::domestic_qwen_workspace).is_some()
        || profile(base_url).is_some_and(|p| matches!(p.catalog.as_str(), "openai-list" | "codex-list"))
}

/// Known Responses-capable entries are selectable. New account IDs remain visible
/// in the inspection catalog until their request protocol has been confirmed.
pub(crate) fn decode_public_catalog(
    profile: &DomesticProfile,
    body: &[u8],
) -> Result<Vec<ModelInfo>, CodexErr> {
    if profile.catalog == "codex-list" {
        return decode_codex_catalog(profile, body);
    }
    let response: PublicCatalog = serde_json::from_slice(body)
        .map_err(|_| CodexErr::InvalidRequest("invalid domestic model list format".into()))?;
    let mut seen = HashSet::new();
    if response.data.iter().any(|model| model.id.trim().is_empty() || !seen.insert(&model.id)) {
        return Err(CodexErr::InvalidRequest("invalid or duplicate domestic model ID".into()));
    }
    Ok(response.data.iter().map(|entry| {
        if let Some(known) = profile.models.iter().find(|m| m.id == entry.id) {
            let mut model = model_info(known);
            if let Some(context) = entry.context_window.filter(|n| *n > 0) {
                model.context_window = Some(context);
                model.max_context_window = Some(context);
            }
            if let Some(modalities) = &entry.input_modalities {
                if !modalities.iter().any(|m| m == "image") {
                    model.input_modalities = vec![InputModality::Text];
                    model.supports_image_detail_original = false;
                }
            }
            if let Some(effort) = &entry.effort {
                model.supported_reasoning_levels.retain(|level| effort.supported_levels.contains(&level.effort));
                model.default_reasoning_level = model.supported_reasoning_levels.iter()
                    .find(|level| level.effort == effort.default_level)
                    .map(|level| level.effort.clone())
                    .or_else(|| model.supported_reasoning_levels.first().map(|level| level.effort.clone()));
            }
            model
        } else {
            let mut model = model_info_from_slug(&entry.id);
            model.visibility = ModelVisibility::Hide;
            model.priority = 1000;
            model.shell_type = ConfigShellToolType::Disabled;
            model.context_window = entry.context_window.filter(|n| *n > 0);
            model.max_context_window = model.context_window;
            model.input_modalities = vec![InputModality::Text];
            model.supports_reasoning_summary_parameter = false;
            model.description = Some("已发现新模型；Responses 协议及任务能力待核验".into());
            model
        }
    }).collect())
}

#[derive(Deserialize)]
struct NativeCatalog {
    models: Vec<NativeModel>,
}

// Read the vendor's declared capability fields, not arbitrary instructions,
// agent policies, hosted tools, routing or other privileged Codex metadata.
#[derive(Deserialize)]
struct NativeModel {
    slug: String,
    context_window: Option<i64>,
    max_context_window: Option<i64>,
    supported_in_api: Option<bool>,
    visibility: Option<ModelVisibility>,
    shell_type: Option<ConfigShellToolType>,
    input_modalities: Option<Vec<InputModality>>,
    #[serde(default)]
    supported_reasoning_levels: Vec<ReasoningEffortPreset>,
    default_reasoning_level: Option<ReasoningEffort>,
    apply_patch_tool_type: Option<ApplyPatchToolType>,
    priority: Option<i32>,
}

fn decode_codex_catalog(profile: &DomesticProfile, body: &[u8]) -> Result<Vec<ModelInfo>, CodexErr> {
    let response: NativeCatalog = serde_json::from_slice(body)
        .map_err(|_| CodexErr::InvalidRequest("invalid native Responses catalog".into()))?;
    let mut seen = HashSet::new();
    let mut models = Vec::with_capacity(response.models.len());
    for entry in response.models {
        if entry.slug.trim().is_empty() || !seen.insert(entry.slug.clone()) {
            return Err(CodexErr::InvalidRequest("invalid or duplicate native model ID".into()));
        }
        let mut model = profile.models.iter().find(|known| known.id == entry.slug)
            .map(model_info).unwrap_or_else(|| model_info_from_slug(&entry.slug));
        let context = entry.context_window.filter(|n| *n > 0);
        let modalities = entry.input_modalities.unwrap_or_default();
        let selectable = entry.supported_in_api == Some(true)
            && entry.visibility == Some(ModelVisibility::List)
            && entry.shell_type == Some(ConfigShellToolType::UnifiedExec)
            && context.is_some()
            && modalities.contains(&InputModality::Text);
        model.supported_in_api = entry.supported_in_api != Some(false);
        model.context_window = context;
        model.max_context_window = entry.max_context_window.filter(|n| *n > 0)
            .or(context);
        model.visibility = if selectable { ModelVisibility::List } else { ModelVisibility::Hide };
        model.shell_type = if selectable { ConfigShellToolType::UnifiedExec } else { ConfigShellToolType::Disabled };
        model.input_modalities = modalities.into_iter().filter(|m| matches!(m, InputModality::Text | InputModality::Image)).collect();
        model.supports_reasoning_summary_parameter = false;
        model.supports_image_detail_original = false;
        model.supported_reasoning_levels = entry.supported_reasoning_levels.into_iter()
            .filter(|level| level.effort != ReasoningEffort::Ultra).collect();
        model.default_reasoning_level = entry.default_reasoning_level
            .filter(|effort| model.supported_reasoning_levels.iter().any(|level| &level.effort == effort));
        model.apply_patch_tool_type = if selectable { entry.apply_patch_tool_type } else { None };
        model.priority = entry.priority.unwrap_or(99);
        model.description = Some(if selectable {
            "官方 Responses 目录声明；真实任务能力待验收"
        } else {
            "目录已发现；协议能力声明不完整，待核验"
        }.into());
        models.push(model);
    }
    Ok(models)
}

pub(crate) const DASHSCOPE_PAGE_SIZE: usize = 100;
pub(crate) const DASHSCOPE_MAX_PAGES: usize = 100;

pub(crate) fn dashscope_page_url(base: &str, page: usize) -> Option<String> {
    let workspace = codex_model_provider_info::domestic_qwen_workspace(base)?;
    Some(format!("https://{workspace}.cn-beijing.maas.aliyuncs.com/api/v1/models?providers=qwen&capabilities=TG&page_no={page}&page_size={DASHSCOPE_PAGE_SIZE}"))
}

#[derive(Deserialize)]
struct DashscopeResponse {
    success: bool,
    output: DashscopePage,
}
#[derive(Deserialize)]
struct DashscopePage {
    total: usize,
    page_no: usize,
    page_size: usize,
    models: Vec<DashscopeModel>,
}
#[derive(Deserialize)]
struct DashscopeModel {
    model: String,
    #[serde(default)]
    features: Vec<String>,
    inference_metadata: Option<DashscopeModalities>,
    model_info: Option<DashscopeContext>,
}
#[derive(Deserialize)]
struct DashscopeModalities {
    #[serde(default)]
    request_modality: Vec<String>,
    #[serde(default)]
    response_modality: Vec<String>,
}
#[derive(Deserialize)]
struct DashscopeContext {
    context_window: Option<i64>,
    max_input_tokens: Option<i64>,
}

/// A failed or inconsistent page cannot publish an incomplete catalog.
#[derive(Default)]
pub(crate) struct DashscopeCatalog {
    total: Option<usize>,
    models: Vec<ModelInfo>,
    seen: HashSet<String>,
}
impl DashscopeCatalog {
    pub(crate) fn push_page(&mut self, profile: &DomesticProfile, page: usize, body: &[u8]) -> Result<bool, CodexErr> {
        let invalid = || CodexErr::InvalidRequest("invalid or incomplete DashScope workspace catalog".into());
        let response: DashscopeResponse = serde_json::from_slice(body).map_err(|_| invalid())?;
        let output = response.output;
        if !response.success || page == 0 || page > DASHSCOPE_MAX_PAGES
            || output.page_no != page || output.page_size != DASHSCOPE_PAGE_SIZE
            || output.total > DASHSCOPE_PAGE_SIZE * DASHSCOPE_MAX_PAGES
            || self.total.is_some_and(|total| total != output.total)
            || self.models.len() != (page - 1) * DASHSCOPE_PAGE_SIZE
            || output.models.len() != output.total.saturating_sub(self.models.len()).min(DASHSCOPE_PAGE_SIZE)
        {
            return Err(invalid());
        }
        self.total = Some(output.total);
        for entry in output.models {
            if entry.model.trim().is_empty() || !self.seen.insert(entry.model.clone()) {
                return Err(invalid());
            }
            let known = profile.models.iter().find(|known| known.id == entry.model);
            let mut model = known.map(model_info).unwrap_or_else(|| model_info_from_slug(&entry.model));
            let modalities = entry.inference_metadata;
            let text = modalities.as_ref().is_some_and(|m| m.request_modality.iter().any(|v| v.eq_ignore_ascii_case("text"))
                && m.response_modality.iter().any(|v| v.eq_ignore_ascii_case("text")));
            let image = modalities.as_ref().is_some_and(|m| m.request_modality.iter().any(|v| v.eq_ignore_ascii_case("image")));
            let selectable = known.is_some() && text && entry.features.iter().any(|v| v == "function-calling");
            let budget = entry.model_info.and_then(|m| {
                let window = m.context_window.filter(|n| *n > 0)?;
                Some(m.max_input_tokens.filter(|n| *n > 0).map_or(window, |n| n.min(window)))
            });
            model.context_window = budget.or_else(|| known.map(|m| m.context));
            model.max_context_window = model.context_window;
            model.visibility = if selectable { ModelVisibility::List } else { ModelVisibility::Hide };
            model.shell_type = if selectable { ConfigShellToolType::UnifiedExec } else { ConfigShellToolType::Disabled };
            model.input_modalities = if selectable && image { vec![InputModality::Text, InputModality::Image] } else { vec![InputModality::Text] };
            model.supports_image_detail_original = false;
            model.supports_reasoning_summary_parameter = false;
            model.description = Some(if selectable { "北京业务空间目录；真实 Responses 任务待验收" } else { "业务空间发现；Responses 或工具能力待核验" }.into());
            self.models.push(model);
        }
        Ok(self.models.len() == output.total)
    }

    pub(crate) fn finish(self) -> Vec<ModelInfo> { self.models }
}

/// The documented candidate list is not an account availability assertion.
pub(crate) fn documented_candidates(profile: &DomesticProfile) -> Vec<ModelInfo> {
    profile.models.iter().map(|entry| {
        let mut model = model_info(entry);
        model.description = Some("官方接口候选模型；实际授权及任务能力需验证".into());
        model
    }).collect()
}

fn model_info(profile: &ProfileModel) -> ModelInfo {
    let mut model = model_info_from_slug(&profile.id);
    model.visibility = ModelVisibility::List;
    model.context_window = Some(profile.context);
    model.max_context_window = Some(profile.context);
    model.supports_reasoning_summary_parameter = false;
    model.input_modalities = if profile.image {
        vec![InputModality::Text, InputModality::Image]
    } else {
        vec![InputModality::Text]
    };
    model.supports_image_detail_original = profile.image;
    model.apply_patch_tool_type = profile.apply_patch.then_some(ApplyPatchToolType::Freeform);
    model.default_reasoning_level = profile.default_effort.clone();
    model.supported_reasoning_levels = profile.efforts.iter().map(|effort| ReasoningEffortPreset {
        effort: effort.clone(),
        description: effort.to_string(),
    }).collect();
    // Do not advertise GPT-only hosted tools or unsupported reasoning levels.
    if profile.id == "MiniMax-M3.1-Flash" {
        model.default_reasoning_level = Some(ReasoningEffort::Low);
        model.supported_reasoning_levels = vec![ReasoningEffortPreset {
            effort: ReasoningEffort::Low,
            description: "默认连续推理".into(),
        }];
    }
    model.description = Some("国内 Responses 目录；真实任务能力待验证".into());
    model
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_only_exact_domestic_base_urls() {
        assert_eq!(profiles().len(), 6);
        assert!(profile(Some("https://api.kimi.com/coding/v1")).is_some());
        assert!(profile(Some("https://api.moonshot.cn/v1/")).is_some());
        assert!(profile(Some("https://api.moonshot.ai/v1")).is_none());
        assert!(profile(Some("https://api.deepseek.com.evil.example")).is_none());
        assert!(profile(Some("http://api.deepseek.com")).is_none());
    }

    #[test]
    fn dashscope_catalog_requires_complete_pages_and_declared_function_calling() {
        let base = "https://llm-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
        let p = profile(Some(base)).unwrap();
        assert!(is_domestic_live_catalog(Some(base)));
        assert!(!is_domestic_live_catalog(Some("https://dashscope.aliyuncs.com/compatible-mode/v1")));
        assert!(dashscope_page_url(base, 2).unwrap().ends_with("page_no=2&page_size=100"));
        let body = br#"{"success":true,"output":{"total":2,"page_no":1,"page_size":100,"models":[{"model":"qwen3.8-max","features":["function-calling"],"inference_metadata":{"request_modality":["Text","Image"],"response_modality":["Text"]},"model_info":{"context_window":1000000,"max_input_tokens":991808}},{"model":"unknown","features":["function-calling"]}]}}"#;
        let mut catalog = DashscopeCatalog::default();
        assert!(catalog.push_page(p, 1, body).unwrap());
        let models = catalog.finish();
        assert_eq!(models[0].visibility, ModelVisibility::List);
        assert_eq!(models[0].context_window, Some(991808));
        assert!(models[0].input_modalities.contains(&InputModality::Image));
        assert_eq!(models[1].visibility, ModelVisibility::Hide);
        let mut partial = DashscopeCatalog::default();
        assert!(partial.push_page(p, 1, br#"{"success":true,"output":{"total":101,"page_no":1,"page_size":100,"models":[]}}"#).is_err());
    }

    #[test]
    fn native_catalog_uses_declared_capabilities_without_importing_privileged_metadata() {
        let profile = profile(Some("https://open.bigmodel.cn/api/v1")).unwrap();
        let models = decode_public_catalog(profile, br#"{"models":[{"slug":"glm-new","supported_in_api":true,"visibility":"list","shell_type":"shell_command","context_window":1048576,"input_modalities":["text","image"],"supported_reasoning_levels":[{"effort":"high","description":"High"}],"default_reasoning_level":"high","apply_patch_tool_type":"freeform","base_instructions":"UNTRUSTED","use_responses_lite":true},{"slug":"incomplete"}]}"#).unwrap();
        assert_eq!(models[0].visibility, ModelVisibility::List);
        assert_eq!(models[0].context_window, Some(1048576));
        assert_eq!(models[0].default_reasoning_level, Some(ReasoningEffort::High));
        assert!(!models[0].use_responses_lite);
        assert!(!models[0].supports_reasoning_summary_parameter);
        assert_eq!(models[1].visibility, ModelVisibility::Hide);
        assert!(decode_public_catalog(profile, br#"{"models":[{"slug":"x"},{"slug":"x"}]}"#).is_err());
        assert!(decode_public_catalog(profile, br#"{"error":"denied"}"#).is_err());
    }

    #[test]
    fn kimi_code_catalog_uses_account_ids_and_keeps_plan_context_conservative() {
        let profile = profile(Some("https://api.kimi.com/coding/v1")).unwrap();
        let models = decode_public_catalog(profile, br#"{"data":[{"id":"k3.1-pro-highspeed"},{"id":"k3-256k"},{"id":"unknown"}]}"#).unwrap();
        assert_eq!(models[0].visibility, ModelVisibility::List);
        assert_eq!(models[0].context_window, Some(262144));
        assert_eq!(models[2].visibility, ModelVisibility::Hide);
    }

    #[test]
    fn live_catalog_never_promotes_unknown_ids() {
        let p = profile(Some("https://api.deepseek.com")).unwrap();
        let models = decode_public_catalog(p, br#"{"data":[{"id":"deepseek-flash"},{"id":"unknown"}]}"#).unwrap();
        assert_eq!(models.len(), 2);
        assert_eq!(models[1].visibility, ModelVisibility::Hide);
        assert_eq!(models[1].shell_type, ConfigShellToolType::Disabled);
        assert_eq!(models[0].slug, "deepseek-flash");
        assert_eq!(models[0].context_window, Some(1048576));
        assert!(!models[0].supports_reasoning_summary_parameter);
        assert_eq!(models[0].default_reasoning_level, Some(ReasoningEffort::High));
        assert!(models[0].input_modalities.contains(&InputModality::Image));
        let live = decode_public_catalog(p, br#"{"data":[{"id":"deepseek-flash","context_window":524288,"input_modalities":["text"],"effort":{"supported_levels":["low"],"default_level":"low"}}]}"#).unwrap();
        assert_eq!(live[0].context_window, Some(524288));
        assert_eq!(live[0].input_modalities, vec![InputModality::Text]);
        assert_eq!(live[0].default_reasoning_level, Some(ReasoningEffort::Low));
        assert!(decode_public_catalog(p, br#"{"data":[{"id":"deepseek-flash"},{"id":"deepseek-flash"}]}"#).is_err());
        assert!(decode_public_catalog(p, br#"{"error":{"message":"denied"}}"#).is_err());
        assert!(decode_public_catalog(p, br#"{"data":[]}"#).unwrap().is_empty());
    }
}

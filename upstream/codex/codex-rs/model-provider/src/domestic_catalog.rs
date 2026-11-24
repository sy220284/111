//! Mainland provider registry. Public model IDs are not Codex model metadata.
use codex_models_manager::model_info::model_info_from_slug;
use codex_protocol::error::CodexErr;
use codex_protocol::openai_models::{InputModality, ModelInfo, ModelVisibility, ReasoningEffort, ReasoningEffortPreset};
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
struct ProfileModel {
    id: String,
    context: i64,
}

fn profiles() -> &'static [DomesticProfile] {
    static PROFILES: OnceLock<Vec<DomesticProfile>> = OnceLock::new();
    PROFILES.get_or_init(|| serde_json::from_str(include_str!("../catalogs/domestic-providers.json"))
        .expect("bundled domestic provider registry must be valid"))
}

pub(crate) fn profile(base_url: Option<&str>) -> Option<&'static DomesticProfile> {
    let base = base_url?.trim_end_matches('/');
    profiles().iter().find(|p| p.base_url == base)
}

#[derive(Deserialize)]
struct PublicCatalog {
    data: Vec<PublicModel>,
}

#[derive(Deserialize)]
struct PublicModel {
    id: String,
}

/// Intersect the account's live list with documented Responses-capable profiles.
/// Unknown IDs must not inherit GPT tools, reasoning controls or context claims.
pub(crate) fn decode_public_catalog(
    profile: &DomesticProfile,
    body: &[u8],
) -> Result<Vec<ModelInfo>, CodexErr> {
    let response: PublicCatalog = serde_json::from_slice(body)
        .map_err(|_| CodexErr::InvalidRequest("invalid domestic model list format".into()))?;
    let mut seen = HashSet::new();
    if response.data.iter().any(|model| model.id.trim().is_empty() || !seen.insert(&model.id)) {
        return Err(CodexErr::InvalidRequest("invalid or duplicate domestic model ID".into()));
    }
    Ok(profile.models.iter().filter(|m| seen.contains(&m.id)).map(model_info).collect())
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
    model.input_modalities = vec![InputModality::Text];
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
        assert_eq!(profiles().len(), 5);
        assert!(profile(Some("https://api.moonshot.cn/v1/")).is_some());
        assert!(profile(Some("https://api.moonshot.ai/v1")).is_none());
        assert!(profile(Some("https://api.deepseek.com.evil.example")).is_none());
        assert!(profile(Some("http://api.deepseek.com")).is_none());
    }

    #[test]
    fn live_catalog_never_promotes_unknown_ids() {
        let p = profile(Some("https://api.deepseek.com")).unwrap();
        let models = decode_public_catalog(p, br#"{"data":[{"id":"deepseek-flash"},{"id":"unknown"}]}"#).unwrap();
        assert_eq!(models.len(), 1);
        assert_eq!(models[0].slug, "deepseek-flash");
        assert_eq!(models[0].context_window, Some(1048576));
        assert!(!models[0].supports_reasoning_summary_parameter);
        assert!(decode_public_catalog(p, br#"{"data":[{"id":"deepseek-flash"},{"id":"deepseek-flash"}]}"#).is_err());
        assert!(decode_public_catalog(p, br#"{"error":{"message":"denied"}}"#).is_err());
        assert!(decode_public_catalog(p, br#"{"data":[]}"#).unwrap().is_empty());
    }
}

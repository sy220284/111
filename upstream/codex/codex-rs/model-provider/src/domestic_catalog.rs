//! Mainland provider registry. Public model IDs are not Codex model metadata.
use codex_models_manager::model_info::model_info_from_slug;
use codex_protocol::error::CodexErr;
use codex_protocol::openai_models::{ModelInfo, ModelVisibility};
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

fn model_info(profile: &ProfileModel) -> ModelInfo {
    let mut model = model_info_from_slug(&profile.id);
    model.visibility = ModelVisibility::List;
    model.context_window = Some(profile.context);
    model.max_context_window = Some(profile.context);
    model.supports_reasoning_summary_parameter = false;
    model.description = Some("Domestic Responses profile; account access and tool execution require validation.".into());
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

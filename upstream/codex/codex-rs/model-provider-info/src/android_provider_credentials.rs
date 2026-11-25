//! Process-local credentials installed by the Android host before JSON-RPC starts.
use super::ModelProviderInfo;
use codex_protocol::error::{CodexErr, Result};
use std::collections::HashMap;
use std::sync::OnceLock;

static KEYS: OnceLock<HashMap<String, (String, String)>> = OnceLock::new();

/// Each entry binds the reserved reference to an exact domestic endpoint.
pub fn install_android_provider_credentials(keys: HashMap<String, (String, String)>) -> std::result::Result<(), &'static str> {
    KEYS.set(keys).map_err(|_| "Android provider credentials already initialized")
}

pub(super) fn lookup(info: &ModelProviderInfo, name: &str) -> Result<String> {
    let (endpoint, secret) = KEYS.get().and_then(|keys| keys.get(name))
        .ok_or_else(|| CodexErr::InvalidRequest("Domestic model credential missing; authorize and restart the Android core".into()))?;
    // No catalog override, cross-provider routing or alternate host for a stored key.
    if info.base_url.as_deref().map(|url| url.trim_end_matches('/')) != Some(endpoint.as_str())
        || info.model_catalog_url.is_some()
        || info.requires_openai_auth
        || info.supports_websockets
        || info.gateway_oauth.is_some()
        || info.auth.is_some()
        || info.aws.is_some()
    {
        return Err(CodexErr::InvalidRequest("Domestic credential endpoint or authentication mismatch".into()));
    }
    Ok(secret.clone())
}

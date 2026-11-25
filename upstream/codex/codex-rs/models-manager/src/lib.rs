pub mod cache;
mod catalog;
pub mod collaboration_mode_presets;
pub(crate) mod config;
pub mod manager;
mod image_input;
pub use image_input::{MediaInputKind, is_definite_media_rejection};
pub mod model_info;
pub mod model_presets;
mod originator;
pub mod test_support;

pub use catalog::CatalogModel;
pub use catalog::ModelOrigin;
pub use codex_protocol::auth::AuthMode;
pub use config::ModelsManagerConfig;
pub use originator::ModelCatalogOriginator;

/// Load the bundled model catalog shipped with `codex-models-manager`.
pub fn bundled_models_response()
-> std::result::Result<codex_protocol::openai_models::ModelsResponse, serde_json::Error> {
    serde_json::from_str(include_str!("../models.json"))
}

/// Convert the client version string to a whole version string (e.g. "1.2.3-alpha.4" -> "1.2.3").
#[cfg(target_os = "android")]
pub fn client_version_to_whole() -> String {
    android_catalog_client_version()
}

#[cfg(any(target_os = "android", test))]
fn android_catalog_client_version() -> String {
    let release = include_str!("../android-client-release.txt").lines().next()
        .expect("missing verified upstream release");
    let version = release.split('-').next().expect("missing upstream version");
    assert_ne!(version, "0.0.0", "invalid upstream catalog version");
    version.to_owned()
}

#[cfg(not(target_os = "android"))]
pub fn client_version_to_whole() -> String {
    format!(
        "{}.{}.{}",
        env!("CARGO_PKG_VERSION_MAJOR"),
        env!("CARGO_PKG_VERSION_MINOR"),
        env!("CARGO_PKG_VERSION_PATCH")
    )
}

#[cfg(test)]
mod android_catalog_version_tests {
    #[test]
    fn android_catalog_version_is_verified_nonzero_semver() {
        let version = super::android_catalog_client_version();
        let parts: Vec<_> = version.split('.').collect();
        assert_eq!(parts.len(), 3);
        assert!(parts.iter().all(|part| part.parse::<u32>().is_ok()));
        assert_ne!(version, "0.0.0");
    }
}

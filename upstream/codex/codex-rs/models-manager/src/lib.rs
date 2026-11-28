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

/// Model discovery and inference share the compiled release version.
/// The Android build applies its verified release baseline before compilation.
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
        let release = include_str!("../android-client-release.txt")
            .lines()
            .next()
            .expect("missing verified upstream release");
        let version = release.split('-').next().expect("missing upstream version");
        let parts: Vec<_> = version.split('.').collect();
        assert_eq!(parts.len(), 3);
        assert!(parts.iter().all(|part| part.parse::<u32>().is_ok()));
        assert_ne!(version, "0.0.0");
        if cfg!(target_os = "android") {
            assert_eq!(super::client_version_to_whole(), version);
            assert_eq!(env!("CARGO_PKG_VERSION"), release);
        }
    }
}

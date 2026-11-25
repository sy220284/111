//! Optimistic image admission with bounded, account-scoped rejection evidence.
//! A catalog's missing or stale modality declaration is not runtime evidence.
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use chrono::{DateTime, Utc};
use codex_protocol::error::{CodexErr, CodexErrorDetails};
use codex_protocol::openai_models::{InputModality, ModelInfo};
use serde::{Deserialize, Serialize};

const RECHECK_HOURS: i64 = 24;
const MAX_RECORDS: usize = 2048;
const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024;
static FILE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaInputKind {
    #[default]
    Image,
    Audio,
}
impl MediaInputKind {
    pub fn modality(self) -> InputModality {
        match self { Self::Image => InputModality::Image, Self::Audio => InputModality::Audio }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Rejection {
    scope: String,
    model: String,
    #[serde(default)]
    kind: MediaInputKind,
    version: String,
    rejected_at: DateTime<Utc>,
}

impl Rejection {
    fn active(&self, now: DateTime<Utc>, version: &str) -> bool {
        self.version == version && self.rejected_at <= now
            && now.signed_duration_since(self.rejected_at) < chrono::Duration::hours(RECHECK_HOURS)
    }
}

#[derive(Debug, Default)]
pub(crate) struct MediaInputEvidence {
    path: Option<PathBuf>,
    memory: Mutex<HashMap<(String, String, MediaInputKind), Rejection>>,
}

impl MediaInputEvidence {
    pub(crate) fn disk(home: PathBuf) -> Self {
        Self {
            path: Some(home.join("image_input_rejections.json")),
            ..Self::default()
        }
    }

    fn load(&self) -> std::io::Result<Vec<Rejection>> {
        let Some(path) = &self.path else { return Ok(Vec::new()); };
        match std::fs::metadata(path) {
            Ok(metadata) if metadata.len() > MAX_FILE_BYTES =>
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "image evidence file too large")),
            Ok(_) => {}
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(err) => return Err(err),
        }
        let bytes = std::fs::read(path)?;
        serde_json::from_slice(&bytes)
            .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidData, err))
    }

    fn merge(&self, disk: Vec<Rejection>) -> Vec<Rejection> {
        let now = Utc::now();
        let version = crate::client_version_to_whole();
        let mut records = self.memory.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        for record in disk {
            let key = (record.scope.clone(), record.model.clone(), record.kind);
            if record.active(now, &version)
                && records.get(&key).is_none_or(|current| current.rejected_at < record.rejected_at)
            {
                records.insert(key, record);
            }
        }
        records.retain(|_, record| record.active(now, &version));
        let mut ordered: Vec<_> = records.values().cloned().collect();
        ordered.sort_by_key(|record| std::cmp::Reverse(record.rejected_at));
        ordered.truncate(MAX_RECORDS);
        if records.len() > MAX_RECORDS {
            records.clear();
            records.extend(ordered.iter().cloned().map(|record|
                ((record.scope.clone(), record.model.clone(), record.kind), record)));
        }
        ordered
    }

    fn snapshot(&self) -> Vec<Rejection> {
        let _guard = FILE_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let disk = match self.load() {
            Ok(records) => records,
            Err(error) => {
                tracing::warn!(%error, "image capability evidence read failed; retaining process evidence");
                Vec::new()
            }
        };
        self.merge(disk)
    }

    pub(crate) fn rejected(&self, scope: &str, model: &str, kind: MediaInputKind) -> bool {
        self.snapshot().iter().any(|entry| entry.scope == scope && entry.model == model && entry.kind == kind)
    }

    pub(crate) fn apply(&self, scope: &str, models: &mut [ModelInfo]) {
        let records = self.snapshot();
        for model in models {
            // Do not change admission, tool privileges, audio or any other modality.
            if !model.input_modalities.contains(&InputModality::Text) {
                continue;
            }
            for kind in [MediaInputKind::Image, MediaInputKind::Audio] {
                let rejected = records.iter().any(|entry| entry.scope == scope && entry.model == model.slug && entry.kind == kind);
                let modality = kind.modality();
                model.input_modalities.retain(|value| *value != modality);
                if !rejected { model.input_modalities.push(modality); }
            }
            // Original-detail is a separate wire capability, not implied by vision.
            model.supports_image_detail_original = false;
        }
    }

    pub(crate) fn record(&self, scope: &str, model: &str, kind: MediaInputKind) -> std::io::Result<()> {
        let _guard = FILE_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let record = Rejection {
            scope: scope.to_owned(),
            model: model.to_owned(),
            kind,
            version: crate::client_version_to_whole(),
            rejected_at: Utc::now(),
        };
        self.memory.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert((scope.to_owned(), model.to_owned(), kind), record);
        let disk = self.load()?;
        let records = self.merge(disk);
        if let Some(path) = &self.path {
            let bytes = serde_json::to_vec(&records)
                .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidData, err))?;
            // Private CODEX_HOME, same-directory atomic rename; never alter models_cache.json.
            let temporary = path.with_extension(format!("{}.tmp", std::process::id()));
            std::fs::write(&temporary, bytes)?;
            if let Err(err) = std::fs::rename(&temporary, path) {
                if let Err(cleanup) = std::fs::remove_file(&temporary) {
                    tracing::warn!(%cleanup, "image evidence temporary file cleanup failed");
                }
                return Err(err);
            }
        }
        Ok(())
    }
}

/// Only explicit model-wide image rejection counts. Invalid images, tool-specific
/// schemas, detail, media size, auth, policy, network and quota do not.
pub fn is_definite_media_rejection(error: &CodexErr, kind: MediaInputKind) -> bool {
    let body = match error.details() {
        CodexErrorDetails::InvalidRequest(message) => message.as_str(),
        CodexErrorDetails::InvalidAudioInput { message, per_recording: false, .. } if kind == MediaInputKind::Audio => message.as_str(),
        CodexErrorDetails::UnexpectedStatus(response) if matches!(response.status.as_u16(), 400 | 415 | 422) =>
            response.body.as_str(),
        _ => return false,
    };
    let parsed = serde_json::from_str::<serde_json::Value>(body).ok();
    let envelope = parsed.as_ref().map(|value| value.get("error").unwrap_or(value));
    let code = envelope.and_then(|value| value.get("code")).and_then(serde_json::Value::as_str)
        .unwrap_or("").to_ascii_lowercase();
    let message = envelope.and_then(|value| value.get("message")).and_then(serde_json::Value::as_str)
        .unwrap_or(body).to_ascii_lowercase();
    if ["tool", "function", "format", "size", "resolution", "detail", "base64", "mime",
        "decode", "corrupt", "invalid image", "invalid audio", "duration", "codec", "sample rate", "bitrate", "时长", "编码", "content policy", "safety", "quota", "rate limit",
        "authentication", "permission", "too large", "image_url", "url scheme",
        "格式", "大小", "尺寸", "分辨率", "权限", "限流", "余额", "安全", "工具"].iter().any(|term| message.contains(term)) {
        return false;
    }
    if kind == MediaInputKind::Audio {
        return matches!(code.as_str(), "unsupported_audio_input" | "audio_input_not_supported" | "model_does_not_support_audio")
            || ["model does not support audio", "audio input is not supported by this model",
                "audio input is not supported for this model", "unsupported content type: input_audio",
                "unsupported input type: input_audio", "不支持音频输入", "不支持语音输入"]
                .iter().any(|phrase| message.contains(phrase))
            || (message.contains("only supports text") && message.contains("audio"));
    }
    if matches!(code.as_str(), "unsupported_image_input" | "image_input_not_supported" | "model_does_not_support_images") {
        return true;
    }
    [
        "model does not support images",
        "model does not support image input",
        "model does not support vision",
        "does not support image inputs",
        "image inputs are not supported by this model",
        "image input is not supported by this model",
        "image input is not supported for this model",
        "images are not supported by this model",
        "unsupported content type: input_image",
        "unsupported input type: input_image",
        "不支持图片输入",
        "不支持图像输入",
        "不支持视觉输入",
    ].iter().any(|phrase| message.contains(phrase))
        || (message.contains("only supports text") && (message.contains("image") || message.contains("vision")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model_info::model_info_from_slug;

    #[test]
    fn unknown_models_are_optimistic_and_rejection_is_scoped_and_persistent() {
        let temp = tempfile::tempdir().unwrap();
        let store = MediaInputEvidence::disk(temp.path().to_path_buf());
        let mut model = model_info_from_slug("new-model");
        model.input_modalities = vec![InputModality::Text];
        store.apply("account-a", std::slice::from_mut(&mut model));
        assert!(model.input_modalities.contains(&InputModality::Image));
        assert!(!model.supports_image_detail_original);
        store.record("account-a", "new-model", MediaInputKind::Image).unwrap();
        let reopened = MediaInputEvidence::disk(temp.path().to_path_buf());
        reopened.apply("account-a", std::slice::from_mut(&mut model));
        assert_eq!(model.input_modalities, vec![InputModality::Text, InputModality::Audio]);
        reopened.record("account-a", "new-model", MediaInputKind::Audio).unwrap();
        reopened.apply("account-a", std::slice::from_mut(&mut model));
        assert_eq!(model.input_modalities, vec![InputModality::Text]);
        reopened.apply("account-b", std::slice::from_mut(&mut model));
        assert!(model.input_modalities.contains(&InputModality::Image));
        assert!(!reopened.rejected("account-a", "different-model", MediaInputKind::Image));
    }

    #[test]
    fn rejection_expiry_and_client_upgrade_restore_optimistic_admission() {
        let now = Utc::now();
        let entry = Rejection { scope: "a".into(), model: "m".into(), kind: MediaInputKind::Image, version: "1".into(),
            rejected_at: now - chrono::Duration::hours(RECHECK_HOURS) };
        assert!(!entry.active(now, "1"));
        let fresh = Rejection { rejected_at: now, ..entry };
        assert!(fresh.active(now, "1"));
        assert!(!fresh.active(now, "2"));
    }

    #[test]
    fn only_definite_image_rejections_are_learned() {
        for message in ["This model does not support image inputs",
            r#"{"error":{"code":"unsupported_image_input","message":"Images unsupported"}}"#,
            "Unsupported content type: input_image", "该模型不支持图片输入"] {
            assert!(is_definite_media_rejection(&CodexErr::InvalidRequest(message.into()), MediaInputKind::Image), "{message}");
        }
        for message in ["image too large", "invalid image", "unsupported image format",
            "unsupported image detail", "tool does not support image inputs",
            "model does not support image input: quota exceeded", "invalid API key", "rate limit",
            "model does not support image input with this resolution", "invalid request",
            "该模型不支持图片输入格式", "model does not support image input via image_url"] {
            assert!(!is_definite_media_rejection(&CodexErr::InvalidRequest(message.into()), MediaInputKind::Image), "{message}");
        }
    }
}

#[cfg(test)]
mod audio_tests {
    use super::*;
    #[test]
    fn audio_rejection_is_independent() {
        for message in ["This model does not support audio input", "Unsupported content type: input_audio", "该模型不支持音频输入"] {
            let error = CodexErr::InvalidRequest(message.into());
            assert!(is_definite_media_rejection(&error, MediaInputKind::Audio));
            assert!(!is_definite_media_rejection(&error, MediaInputKind::Image));
        }
        for message in ["audio too large", "unsupported audio format", "audio codec not supported", "model does not support audio input with this sample rate", "tool does not support audio", "该模型不支持音频输入格式", "rate limit"] {
            assert!(!is_definite_media_rejection(&CodexErr::InvalidRequest(message.into()), MediaInputKind::Audio));
        }
        let row: Rejection = serde_json::from_value(serde_json::json!({"scope":"a","model":"m","version":"1","rejected_at":Utc::now()})).unwrap();
        assert_eq!(row.kind, MediaInputKind::Image);
    }
}

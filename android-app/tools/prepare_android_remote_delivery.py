#!/usr/bin/env python3
"""Build-time Android extension; keep the locked upstream tree unchanged."""
import argparse
from pathlib import Path

TYPES = '''
/// Android delivery: a user-selected file inside the registered remote workspace.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, JsonSchema, TS)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[ts(export_to = "v2/")]
pub struct AndroidRemoteFileReadParams {
    pub environment_id: String,
    pub workspace_uri: PathUri,
    pub relative_path: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, JsonSchema, TS)]
#[serde(rename_all = "camelCase")]
#[ts(export_to = "v2/")]
pub struct AndroidRemoteFileReadResponse {
    pub environment_id: String,
    pub path_uri: PathUri,
    pub data_base64: String,
}
'''

REQUEST = '''    AndroidRemoteFileRead => "android/remoteFile/read" {
        params: v2::AndroidRemoteFileReadParams,
        serialization: global_shared_read("environment"),
        response: v2::AndroidRemoteFileReadResponse,
    },
'''

DISPATCH = '''            ClientRequest::AndroidRemoteFileRead { params, .. } => {
                self.environment_processor.android_remote_file_read(params).await
            }
'''

PROCESSOR = r'''    pub(crate) async fn android_remote_file_read(
        &self,
        params: codex_app_server_protocol::AndroidRemoteFileReadParams,
    ) -> Result<Option<ClientResponsePayload>, JSONRPCErrorError> {
        use base64::Engine;
        use codex_exec_server::GetMetadataOptions;
        use codex_exec_server::ReadFileOptions;
        const MAX_BYTES: u64 = 8 * 1024 * 1024;
        let id = params.environment_id;
        if id.is_empty() || id == codex_exec_server::LOCAL_ENVIRONMENT_ID {
            return Err(invalid_request("remote environment required"));
        }
        let env = self.environment_manager.get_environment(&id)
            .ok_or_else(|| invalid_request("unknown remote environment"))?;
        let info = env.force_info().await.map_err(|e| internal_error(e.to_string()))?;
        let root = info.cwd.ok_or_else(|| invalid_request("remote workspace unavailable"))?;
        if root != params.workspace_uri {
            return Err(invalid_request("remote workspace changed"));
        }
        let relative = params.relative_path;
        if relative.is_empty() || relative.len() > 4096 ||
            relative.chars().any(char::is_control) ||
            relative.split(['/', '\\']).any(|p| p.is_empty() || p == "." || p == ".." || p.contains(':')) {
            return Err(invalid_request("invalid remote relative path"));
        }
        let target = root.join_descendant(&relative).map_err(|e| invalid_request(e.to_string()))?;
        if target == root || !target.starts_with(&root) {
            return Err(invalid_request("file outside remote workspace"));
        }
        let fs = env.get_filesystem();
        let metadata = fs.get_metadata(&target, GetMetadataOptions { follow_symlinks: false }, None)
            .await.map_err(|e| internal_error(e.to_string()))?;
        if metadata.is_symlink || !metadata.is_file || metadata.size > MAX_BYTES {
            return Err(invalid_request("remote file must be regular, no symlinks, at most 8 MiB"));
        }
        let bytes = fs.read_file(&target, ReadFileOptions { follow_symlinks: false }, None)
            .await.map_err(|e| internal_error(e.to_string()))?;
        if bytes.len() as u64 > MAX_BYTES {
            return Err(invalid_request("remote file exceeds 8 MiB"));
        }
        Ok(Some(codex_app_server_protocol::AndroidRemoteFileReadResponse {
            environment_id: id,
            path_uri: target,
            data_base64: base64::engine::general_purpose::STANDARD.encode(bytes),
        }.into()))
    }

'''

CHANGES = (
    ("app-server-protocol/src/protocol/v2/environment.rs",
     "/// Parameters for reading the current status of one configured environment.",
     TYPES + "\n/// Parameters for reading the current status of one configured environment."),
    ("app-server-protocol/src/protocol/common.rs",
     '    #[experimental("environment/status")]\n',
     REQUEST + '    #[experimental("environment/status")]\n'),
    ("app-server/src/message_processor.rs",
     "            ClientRequest::EnvironmentStatus { params, .. } => {\n",
     DISPATCH + "            ClientRequest::EnvironmentStatus { params, .. } => {\n"),
    ("app-server/src/request_processors/environment_processor.rs",
     "    pub(crate) async fn environment_status(\n",
     PROCESSOR + "    pub(crate) async fn environment_status(\n"),
)

def adapt(source, old, new):
    if source.count(new) == 1:
        return source
    if source.count(old) != 1:
        raise ValueError("Android remote delivery source contract changed: " + old)
    return source.replace(old, new)

def run(root, check=False):
    changes = []
    for relative, old, new in CHANGES:
        path = root / relative
        before = path.read_text(encoding="utf-8")
        changes.append((path, before, adapt(before, old, new)))
    if not check:
        for path, before, after in changes:
            if before != after:
                path.write_text(after, encoding="utf-8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    run(args.root, args.check)

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
        // Use a distinct method so an older executor cannot silently ignore the limit.
        let response = env.android_read_delivery_file(target.clone()).await.map_err(|e|
            internal_error(format!("安全文件交付需要支持 androidDeliveryRead 的远端执行器：{e}")))?;
        if response.data_base64.len() > (((MAX_BYTES + 2) / 3) * 4) as usize {
            return Err(invalid_request("remote file exceeds 8 MiB"));
        }
        let bytes = base64::engine::general_purpose::STANDARD.decode(response.data_base64)
            .map_err(|e| internal_error(e.to_string()))?;
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

# The remote executor must implement the same explicit RPC. Ordinary upstream reads
# keep their existing 512 MiB limit and behavior. No changes to locked source in Git.
EXEC_CHANGES = (
    ("exec-server/src/local_file_system_read.rs",
     "    let cancelled = CancellationToken::new();",
     """    read_file_limited(path, options, MAX_READ_FILE_BYTES).await
}

pub(crate) async fn read_file_limited(
    path: AbsolutePathBuf,
    options: ReadFileOptions,
    max_bytes: u64,
) -> io::Result<Vec<u8>> {
    let cancelled = CancellationToken::new();"""),
    ("exec-server/src/local_file_system_read.rs",
     "        if metadata.len() > MAX_READ_FILE_BYTES {\n            return Err(file_too_large_error());\n        }\n        let mut bytes = Vec::with_capacity(metadata.len() as usize);\n        CancellableReader {\n            reader: file.take(MAX_READ_FILE_BYTES + 1),",
     "        if metadata.len() > max_bytes {\n            return Err(file_too_large_error(max_bytes));\n        }\n        let mut bytes = Vec::with_capacity(metadata.len() as usize);\n        CancellableReader {\n            reader: file.take(max_bytes + 1),"),
    ("exec-server/src/local_file_system_read.rs",
     "        if bytes.len() as u64 > MAX_READ_FILE_BYTES {\n            return Err(file_too_large_error());",
     "        if bytes.len() as u64 > max_bytes {\n            return Err(file_too_large_error(max_bytes));"),
    ("exec-server/src/local_file_system_read.rs",
     "fn file_too_large_error() -> io::Error {",
     "fn file_too_large_error(max_bytes: u64) -> io::Error {"),
    ("exec-server/src/local_file_system_read.rs",
     "limit is {MAX_READ_FILE_BYTES} bytes",
     "limit is {max_bytes} bytes"),
    ("exec-server/src/client.rs", "    pub async fn fs_read_file(\n",
     """    pub(crate) async fn android_read_delivery_file(
        &self,
        path: codex_utils_path_uri::PathUri,
    ) -> Result<FsReadFileResponse, ExecServerError> {
        self.call("fs/androidDeliveryRead", &FsReadFileParams {
            path, follow_symlinks: Some(false), sandbox: None,
        }).await
    }

    pub async fn fs_read_file(
"""),
    ("exec-server/src/environment.rs", "    pub fn get_filesystem(&self) -> Arc<dyn ExecutorFileSystem> {",
     """    pub async fn android_read_delivery_file(
        &self,
        path: codex_utils_path_uri::PathUri,
    ) -> Result<crate::FsReadFileResponse, ExecServerError> {
        let client = self.remote_client.as_ref().ok_or_else(||
            ExecServerError::Protocol("remote environment required".to_owned()))?;
        client.get().await?.android_read_delivery_file(path).await
    }

    pub fn get_filesystem(&self) -> Arc<dyn ExecutorFileSystem> {"""),
    ("exec-server/src/server/registry.rs",
     "    router.request(\n        FS_READ_FILE_METHOD,",
     """    router.request(
        "fs/androidDeliveryRead",
        |handler: Arc<ExecServerHandler>, params: crate::protocol::FsReadFileParams| async move {
            handler.android_read_delivery_file(params).await
        },
    );
    router.request(
        FS_READ_FILE_METHOD,"""),
    ("exec-server/src/server/handler.rs", "    pub(crate) async fn fs_read_file(\n",
     """    pub(crate) async fn android_read_delivery_file(
        &self,
        params: FsReadFileParams,
    ) -> Result<FsReadFileResponse, JSONRPCErrorError> {
        self.require_initialized_for("filesystem")?;
        self.file_system.android_read_delivery_file(params).await
    }

    pub(crate) async fn fs_read_file(
"""),
    ("exec-server/src/server/file_system_handler.rs", "    pub(crate) async fn read_file(\n",
     """    pub(crate) async fn android_read_delivery_file(
        &self,
        params: FsReadFileParams,
    ) -> Result<FsReadFileResponse, JSONRPCErrorError> {
        // Dedicated, non-sandboxed remote workspace delivery: never follow links
        // or allocate according to an untrusted file's unbounded current length.
        if params.follow_symlinks != Some(false) || params.sandbox.is_some() {
            return Err(invalid_request("delivery requires no-follow and no sandbox override"));
        }
        let path = params.path.to_abs_path().map_err(|e| invalid_request(e.to_string()))?;
        let bytes = crate::local_file_system_read::read_file_limited(
            path, ReadFileOptions { follow_symlinks: false }, 8 * 1024 * 1024,
        ).await.map_err(map_fs_error)?;
        Ok(FsReadFileResponse { data_base64: STANDARD.encode(bytes) })
    }

    pub(crate) async fn read_file(
"""),
    ("exec-server/src/local_file_system_read_tests.rs", "#[cfg(unix)]\n#[test]\nfn cancellation_stops_a_reader_after_its_first_chunk",
     """#[tokio::test]
async fn android_delivery_enforces_the_actual_read_limit() -> anyhow::Result<()> {
    let directory = TempDir::new()?;
    let path = directory.path().canonicalize()?.join("delivery.bin");
    let limit = 8 * 1024 * 1024;
    std::fs::write(&path, b"hello")?;
    let read = || super::read_file_limited(
        PathUri::from_host_native_path(&path).unwrap().to_abs_path().unwrap(),
        ReadFileOptions { follow_symlinks: false }, limit,
    );
    assert_eq!(read().await?, b"hello".to_vec());
    std::fs::File::create(&path)?.set_len(limit + 1)?;
    assert_eq!(read().await.unwrap_err().kind(), std::io::ErrorKind::InvalidInput);
    Ok(())
}

#[cfg(unix)]
#[test]
fn cancellation_stops_a_reader_after_its_first_chunk"""),
)

def adapt(source, old, new):
    if source.count(new) == 1:
        return source
    if source.count(old) != 1:
        raise ValueError("Android remote delivery source contract changed: " + old)
    return source.replace(old, new)

def run(root, check=False, exec_only=False):
    # Compose all edits to the same source before writing anything; fail atomically
    # on upstream drift, including when this script is applied twice.
    changes = {}
    for relative, old, new in EXEC_CHANGES + (() if exec_only else CHANGES):
        path = root / relative
        if path not in changes:
            before = path.read_text(encoding="utf-8")
            changes[path] = (before, before)
        before, current = changes[path]
        changes[path] = (before, adapt(current, old, new))
    if not check:
        for path, (before, after) in changes.items():
            if before != after:
                path.write_text(after, encoding="utf-8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--exec-only", action="store_true", help="prepare a remote workstation executor")
    args = parser.parse_args()
    run(args.root, args.check, args.exec_only)

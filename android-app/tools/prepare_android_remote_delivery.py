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

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, JsonSchema, TS)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[ts(export_to = "v2/")]
pub struct AndroidRemoteFileChunkParams {
    pub environment_id: String,
    pub workspace_uri: PathUri,
    pub relative_path: String,
    pub offset: u64,
    pub version: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, JsonSchema, TS)]
#[serde(rename_all = "camelCase")]
#[ts(export_to = "v2/")]
pub struct AndroidRemoteFileChunkResponse {
    pub environment_id: String,
    pub path_uri: PathUri,
    pub offset: u64,
    pub size: u64,
    pub version: String,
    pub sha256: Option<String>,
    pub data_base64: String,
}
'''

REQUEST = '''    AndroidRemoteFileRead => "android/remoteFile/read" {
        params: v2::AndroidRemoteFileReadParams,
        serialization: global_shared_read("environment"),
        response: v2::AndroidRemoteFileReadResponse,
    },
    AndroidRemoteFileChunk => "android/remoteFile/readChunk" {
        params: v2::AndroidRemoteFileChunkParams,
        serialization: global_shared_read("environment"),
        response: v2::AndroidRemoteFileChunkResponse,
    },
'''

DISPATCH = '''            ClientRequest::AndroidRemoteFileRead { params, .. } => {
                self.environment_processor.android_remote_file_read(params).await
            }
            ClientRequest::AndroidRemoteFileChunk { params, .. } => {
                self.environment_processor.android_remote_file_chunk(params).await
            }
'''

PROCESSOR = r'''    async fn android_delivery_target(
        &self,
        id: &str,
        workspace_uri: &codex_utils_path_uri::PathUri,
        relative: &str,
    ) -> Result<(Arc<codex_exec_server::Environment>, codex_utils_path_uri::PathUri), JSONRPCErrorError> {
        if id.is_empty() || id == codex_exec_server::LOCAL_ENVIRONMENT_ID {
            return Err(invalid_request("remote environment required"));
        }
        let env = self.environment_manager.get_environment(id)
            .ok_or_else(|| invalid_request("unknown remote environment"))?;
        let info = env.force_info().await.map_err(|e| internal_error(e.to_string()))?;
        let root = info.cwd.ok_or_else(|| invalid_request("remote workspace unavailable"))?;
        if &root != workspace_uri {
            return Err(invalid_request("remote workspace changed"));
        }
        if relative.is_empty() || relative.len() > 4096 ||
            relative.chars().any(char::is_control) ||
            relative.split(['/', '\\']).any(|p| p.is_empty() || p == "." || p == ".." || p.contains(':')) {
            return Err(invalid_request("invalid remote relative path"));
        }
        let target = root.join_descendant(relative).map_err(|e| invalid_request(e.to_string()))?;
        if target == root || !target.starts_with(&root) {
            return Err(invalid_request("file outside remote workspace"));
        }
        Ok((env, target))
    }

    pub(crate) async fn android_remote_file_read(
        &self,
        params: codex_app_server_protocol::AndroidRemoteFileReadParams,
    ) -> Result<Option<ClientResponsePayload>, JSONRPCErrorError> {
        use base64::Engine;
        use codex_exec_server::GetMetadataOptions;
        const MAX_BYTES: u64 = 8 * 1024 * 1024;
        let id = params.environment_id;
        let (env, target) = self.android_delivery_target(&id, &params.workspace_uri, &params.relative_path).await?;
        let fs = env.get_filesystem();
        let metadata = fs.get_metadata(&target, GetMetadataOptions { follow_symlinks: false }, None)
            .await.map_err(|e| internal_error(e.to_string()))?;
        if metadata.is_symlink || !metadata.is_file || metadata.size > MAX_BYTES {
            return Err(invalid_request("remote file must be regular, no symlinks, at most 8 MiB"));
        }
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

    pub(crate) async fn android_remote_file_chunk(
        &self,
        params: codex_app_server_protocol::AndroidRemoteFileChunkParams,
    ) -> Result<Option<ClientResponsePayload>, JSONRPCErrorError> {
        use base64::Engine;
        const MAX_BYTES: u64 = 512 * 1024 * 1024;
        const CHUNK: u64 = 1024 * 1024;
        fn digest(value: &str) -> bool {
            value.len() == 64 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        }
        if params.offset > MAX_BYTES || (params.version.is_none() && params.offset != 0) ||
            params.version.as_deref().is_some_and(|v| !digest(v)) {
            return Err(invalid_request("invalid delivery offset or source version"));
        }
        let (env, target) = self.android_delivery_target(&params.environment_id, &params.workspace_uri,
            &params.relative_path).await?;
        let response = env.android_read_delivery_chunk(codex_exec_server::AndroidDeliveryChunkParams {
            path: target.clone(), offset: params.offset, version: params.version.clone(),
        }).await.map_err(|e| internal_error(format!("分块交付需要支持 androidDeliveryChunk 的远端执行器：{e}")))?;
        if response.offset != params.offset || response.size > MAX_BYTES || response.offset > response.size ||
            !digest(&response.version) ||
            params.version.as_ref().is_some_and(|v| v != &response.version) ||
            (params.version.is_none() && !response.sha256.as_deref().is_some_and(digest)) ||
            (params.version.is_some() && response.sha256.is_some()) ||
            response.data_base64.len() > (((CHUNK + 2) / 3) * 4) as usize {
            return Err(invalid_request("invalid remote delivery receipt"));
        }
        let bytes = base64::engine::general_purpose::STANDARD.decode(&response.data_base64)
            .map_err(|e| internal_error(e.to_string()))?;
        if bytes.len() as u64 != CHUNK.min(response.size - response.offset) {
            return Err(invalid_request("remote delivery chunk length mismatch"));
        }
        Ok(Some(codex_app_server_protocol::AndroidRemoteFileChunkResponse {
            environment_id: params.environment_id,
            path_uri: target,
            offset: response.offset,
            size: response.size,
            version: response.version,
            sha256: response.sha256,
            data_base64: response.data_base64,
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

    pub(crate) async fn android_read_delivery_chunk(
        &self,
        params: crate::protocol::AndroidDeliveryChunkParams,
    ) -> Result<crate::protocol::AndroidDeliveryChunkResponse, ExecServerError> {
        self.call("fs/androidDeliveryChunk", &params).await
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

    pub async fn android_read_delivery_chunk(
        &self,
        params: crate::AndroidDeliveryChunkParams,
    ) -> Result<crate::AndroidDeliveryChunkResponse, ExecServerError> {
        let client = self.remote_client.as_ref().ok_or_else(||
            ExecServerError::Protocol("remote environment required".to_owned()))?;
        client.get().await?.android_read_delivery_chunk(params).await
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
        "fs/androidDeliveryChunk",
        |handler: Arc<ExecServerHandler>, params: crate::protocol::AndroidDeliveryChunkParams| async move {
            handler.android_read_delivery_chunk(params).await
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

    pub(crate) async fn android_read_delivery_chunk(
        &self,
        params: crate::protocol::AndroidDeliveryChunkParams,
    ) -> Result<crate::protocol::AndroidDeliveryChunkResponse, JSONRPCErrorError> {
        self.require_initialized_for("filesystem")?;
        self.file_system.android_read_delivery_chunk(params).await
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

    pub(crate) async fn android_read_delivery_chunk(
        &self,
        params: crate::protocol::AndroidDeliveryChunkParams,
    ) -> Result<crate::protocol::AndroidDeliveryChunkResponse, JSONRPCErrorError> {
        let path = params.path.to_abs_path().map_err(|e| invalid_request(e.to_string()))?;
        crate::local_file_system_read::read_delivery_chunk(path, params.offset, params.version)
            .await.map_err(map_fs_error)
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
#[tokio::test]
async fn android_delivery_rejects_leaf_and_parent_links() -> anyhow::Result<()> {
    let directory = TempDir::new()?;
    let root = directory.path().canonicalize()?;
    std::fs::create_dir(root.join("real"))?;
    std::fs::write(root.join("real/file"), b"private")?;
    std::os::unix::fs::symlink(root.join("real"), root.join("linked-dir"))?;
    std::os::unix::fs::symlink(root.join("real/file"), root.join("linked-file"))?;
    for path in [root.join("linked-dir/file"), root.join("linked-file")] {
        let uri = PathUri::from_host_native_path(&path)?;
        assert!(super::read_file_limited(uri.to_abs_path()?,
            ReadFileOptions { follow_symlinks: false }, 8 * 1024 * 1024).await.is_err());
    }
    Ok(())
}

#[tokio::test]
async fn android_delivery_chunk_returns_verified_digest_and_bounded_ranges() -> anyhow::Result<()> {
    use base64::Engine as _;
    use sha2::{Digest, Sha256};
    use std::io::{Seek, SeekFrom, Write};
    let directory = TempDir::new()?;
    let path = directory.path().canonicalize()?.join("delivery-large.bin");
    let size = 9 * 1024 * 1024 + 7;
    let mut file = std::fs::File::create(&path)?;
    file.set_len(size)?;
    file.write_all(b"hello")?;
    file.seek(SeekFrom::Start(size - 3))?;
    file.write_all(b"end")?;
    drop(file);
    let uri = PathUri::from_host_native_path(&path)?;
    let first = super::read_delivery_chunk(uri.to_abs_path()?, 0, None).await?;
    assert_eq!(first.size, size);
    let mut digest = Sha256::new();
    let mut offset = 0;
    loop {
        let receipt = if offset == 0 { first.clone() } else {
            super::read_delivery_chunk(uri.to_abs_path()?, offset, Some(first.version.clone())).await?
        };
        let data = base64::engine::general_purpose::STANDARD.decode(receipt.data_base64)?;
        assert_eq!(data.len() as u64, (1024 * 1024).min(size - offset));
        digest.update(&data);
        offset += data.len() as u64;
        if offset == size { break; }
    }
    assert_eq!(first.sha256, Some(format!("{:x}", digest.finalize())));
    std::fs::OpenOptions::new().write(true).open(&path)?.set_len(size + 1)?;
    assert!(super::read_delivery_chunk(uri.to_abs_path()?, 1, Some(first.version)).await.is_err());
    assert!(super::read_delivery_chunk(uri.to_abs_path()?, 1, None).await.is_err());
    Ok(())
}

#[cfg(unix)]
#[tokio::test]
async fn android_delivery_chunk_rejects_leaf_and_parent_links() -> anyhow::Result<()> {
    let directory = TempDir::new()?;
    let root = directory.path().canonicalize()?;
    std::fs::create_dir(root.join("real"))?;
    std::fs::write(root.join("real/file"), b"private")?;
    std::os::unix::fs::symlink(root.join("real"), root.join("linked-dir"))?;
    std::os::unix::fs::symlink(root.join("real/file"), root.join("linked-file"))?;
    for path in [root.join("linked-dir/file"), root.join("linked-file")] {
        let uri = PathUri::from_host_native_path(&path)?;
        assert!(super::read_delivery_chunk(uri.to_abs_path()?, 0, None).await.is_err());
    }
    Ok(())
}

#[cfg(unix)]
#[test]
fn cancellation_stops_a_reader_after_its_first_chunk"""),
)

# Explicit offset protocol and digest dependency (already present in the locked workspace).
EXEC_CHANGES += (
    ("exec-server-protocol/src/protocol.rs",
     "/// Defaults preserve read-only opens for legacy callers.",
     "/// Android bounded read of an ordinary remote file. First request has no\n/// version and returns a full-file digest; subsequent requests use that\n/// source version and receive only a bounded chunk.\n#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]\n#[serde(rename_all = \"camelCase\", deny_unknown_fields)]\npub struct AndroidDeliveryChunkParams {\n    pub path: PathUri,\n    pub offset: u64,\n    pub version: Option<String>,\n}\n\n#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]\n#[serde(rename_all = \"camelCase\")]\npub struct AndroidDeliveryChunkResponse {\n    pub offset: u64,\n    pub size: u64,\n    pub version: String,\n    pub sha256: Option<String>,\n    pub data_base64: String,\n}\n\n/// Defaults preserve read-only opens for legacy callers."),
    ("exec-server/src/lib.rs",
     "pub use protocol::FsReadFileResponse;",
     "pub use protocol::AndroidDeliveryChunkParams;\npub use protocol::AndroidDeliveryChunkResponse;\npub use protocol::FsReadFileResponse;"),
    ("exec-server/Cargo.toml",
     "serde_json = { workspace = true }\nthiserror = { workspace = true }",
     "serde_json = { workspace = true }\nsha2 = { workspace = true }\nthiserror = { workspace = true }"),
    ("Cargo.lock",
     "name = \"codex-exec-server\"\nversion = \"0.0.0\"\ndependencies = [\n \"anyhow\",\n \"arc-swap\",\n \"axum\",\n \"base64 0.22.1\",\n \"bytes\",\n \"clatter\",\n \"codex-api\",\n \"codex-build-info\",\n \"codex-config\",\n \"codex-core-plugin-common\",\n \"codex-exec-server-protocol\",\n \"codex-exec-server-test-support\",\n \"codex-file-system\",\n \"codex-file-watcher\",\n \"codex-http-client\",\n \"codex-linux-sandbox\",\n \"codex-mxc-sandbox\",\n \"codex-network-proxy\",\n \"codex-otel\",\n \"codex-protocol\",\n \"codex-sandboxing\",\n \"codex-shell-command\",\n \"codex-skills\",\n \"codex-test-binary-support\",\n \"codex-uds\",\n \"codex-utils-absolute-path\",\n \"codex-utils-cargo-bin\",\n \"codex-utils-home-dir\",\n \"codex-utils-path-uri\",\n \"codex-utils-process\",\n \"codex-utils-pty\",\n \"codex-utils-redacted-string\",\n \"codex-utils-rustls-provider\",\n \"codex-websocket-auth\",\n \"codex-websocket-client\",\n \"codex-windows-sandbox-test-support\",\n \"ctor 0.6.3\",\n \"dirs\",\n \"futures\",\n \"http 1.4.0\",\n \"libc\",\n \"notify\",\n \"opentelemetry\",\n \"opentelemetry_sdk\",\n \"pretty_assertions\",\n \"prost\",\n \"rcgen\",\n \"rustix 1.1.4\",\n \"rustls\",\n \"serde\",\n \"serde_json\",\n \"serial_test\",\n \"tempfile\",\n \"test-case\",\n \"thiserror 2.0.18\",\n \"tokio\",\n \"tokio-tungstenite\",\n \"tokio-util\",\n \"toml 0.9.11+spec-1.1.0\",\n \"tracing\",\n \"tracing-opentelemetry\",\n \"tracing-subscriber\",\n \"url\",\n \"uuid\",\n \"windows-sys 0.61.2\",\n \"wiremock\",\n]",
     "name = \"codex-exec-server\"\nversion = \"0.0.0\"\ndependencies = [\n \"anyhow\",\n \"arc-swap\",\n \"axum\",\n \"base64 0.22.1\",\n \"bytes\",\n \"clatter\",\n \"codex-api\",\n \"codex-build-info\",\n \"codex-config\",\n \"codex-core-plugin-common\",\n \"codex-exec-server-protocol\",\n \"codex-exec-server-test-support\",\n \"codex-file-system\",\n \"codex-file-watcher\",\n \"codex-http-client\",\n \"codex-linux-sandbox\",\n \"codex-mxc-sandbox\",\n \"codex-network-proxy\",\n \"codex-otel\",\n \"codex-protocol\",\n \"codex-sandboxing\",\n \"codex-shell-command\",\n \"codex-skills\",\n \"codex-test-binary-support\",\n \"codex-uds\",\n \"codex-utils-absolute-path\",\n \"codex-utils-cargo-bin\",\n \"codex-utils-home-dir\",\n \"codex-utils-path-uri\",\n \"codex-utils-process\",\n \"codex-utils-pty\",\n \"codex-utils-redacted-string\",\n \"codex-utils-rustls-provider\",\n \"codex-websocket-auth\",\n \"codex-websocket-client\",\n \"codex-windows-sandbox-test-support\",\n \"ctor 0.6.3\",\n \"dirs\",\n \"futures\",\n \"http 1.4.0\",\n \"libc\",\n \"notify\",\n \"opentelemetry\",\n \"opentelemetry_sdk\",\n \"pretty_assertions\",\n \"prost\",\n \"rcgen\",\n \"rustix 1.1.4\",\n \"rustls\",\n \"serde\",\n \"serde_json\",\n \"serial_test\",\n \"sha2 0.10.9\",\n \"tempfile\",\n \"test-case\",\n \"thiserror 2.0.18\",\n \"tokio\",\n \"tokio-tungstenite\",\n \"tokio-util\",\n \"toml 0.9.11+spec-1.1.0\",\n \"tracing\",\n \"tracing-opentelemetry\",\n \"tracing-subscriber\",\n \"url\",\n \"uuid\",\n \"windows-sys 0.61.2\",\n \"wiremock\",\n]"),
    ("exec-server/src/local_file_system_read.rs",
     "struct CancellableReader<R> {",
     "const DELIVERY_CHUNK_BYTES: u64 = 1024 * 1024;\n\nfn delivery_version(metadata: &std::fs::Metadata) -> io::Result<String> {\n    use sha2::{Digest, Sha256};\n    let mut identity = format!(\"{}:{:?}\", metadata.len(), metadata.modified()?);\n    #[cfg(unix)]\n    {\n        use std::os::unix::fs::MetadataExt;\n        identity.push_str(&format!(\":{}:{}:{}:{}\", metadata.dev(), metadata.ino(),\n            metadata.ctime(), metadata.ctime_nsec()));\n    }\n    #[cfg(windows)]\n    {\n        identity.push_str(&format!(\":{:?}\", metadata.created()?));\n    }\n    Ok(format!(\"{:x}\", Sha256::digest(identity.as_bytes())))\n}\n\npub(crate) async fn read_delivery_chunk(\n    path: AbsolutePathBuf,\n    offset: u64,\n    expected_version: Option<String>,\n) -> io::Result<crate::protocol::AndroidDeliveryChunkResponse> {\n    use base64::Engine as _;\n    use sha2::{Digest, Sha256};\n    use std::io::{Seek, SeekFrom};\n    let cancelled = CancellationToken::new();\n    let _cancel_on_drop = cancelled.clone().drop_guard();\n    tokio::task::spawn_blocking(move || {\n        let invalid = |message| io::Error::new(io::ErrorKind::InvalidInput, message);\n        if cancelled.is_cancelled() {\n            return Err(io::Error::other(\"file read cancelled\"));\n        }\n        let mut file = no_follow::open_file(path.as_path())?;\n        let metadata = file.metadata()?;\n        let size = metadata.len();\n        if !metadata.is_file() || size > MAX_READ_FILE_BYTES || offset > size ||\n            (expected_version.is_none() && offset != 0) {\n            return Err(invalid(\"invalid delivery size, file type or offset\"));\n        }\n        let version = delivery_version(&metadata)?;\n        if expected_version.as_ref().is_some_and(|expected| expected != &version) {\n            return Err(invalid(\"remote delivery source changed\"));\n        }\n        let count = DELIVERY_CHUNK_BYTES.min(size - offset) as usize;\n        let mut bytes = Vec::with_capacity(count);\n        let sha256 = if expected_version.is_none() {\n            // Hash the entire opened file once, retaining only its first chunk.\n            // Limit growth and cancel between bounded reads.\n            let mut digest = Sha256::new();\n            let mut total = 0u64;\n            let mut buffer = [0u8; 64 * 1024];\n            let mut reader = CancellableReader { reader: (&mut file).take(size + 1),\n                cancelled: cancelled.clone() };\n            loop {\n                let read = reader.read(&mut buffer)?;\n                if read == 0 { break; }\n                total += read as u64;\n                if total > size { return Err(invalid(\"remote delivery source grew\")); }\n                digest.update(&buffer[..read]);\n                let keep = (count - bytes.len()).min(read);\n                bytes.extend_from_slice(&buffer[..keep]);\n            }\n            if total != size { return Err(invalid(\"remote delivery source shrank\")); }\n            Some(format!(\"{:x}\", digest.finalize()))\n        } else {\n            file.seek(SeekFrom::Start(offset))?;\n            bytes.resize(count, 0);\n            CancellableReader { reader: &mut file, cancelled: cancelled.clone() }\n                .read_exact(&mut bytes)?;\n            None\n        };\n        if delivery_version(&file.metadata()?)? != version {\n            return Err(invalid(\"remote delivery source changed during read\"));\n        }\n        Ok(crate::protocol::AndroidDeliveryChunkResponse {\n            offset, size, version, sha256,\n            data_base64: base64::engine::general_purpose::STANDARD.encode(bytes),\n        })\n    }).await.map_err(|error| io::Error::other(format!(\"filesystem task failed: {error}\")))?\n}\n\nstruct CancellableReader<R> {"),
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

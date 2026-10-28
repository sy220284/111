// @bun @bytecode
// Claude Code is a Beta product per Anthropic's Commercial Terms of Service.
// By using Claude Code, you agree that all code acceptance or rejection decisions you make,
// and the associated conversations in context, constitute Feedback under Anthropic's Commercial Terms,
// and may be used to improve Anthropic's products, including training models.
// You are responsible for reviewing any code suggestions before use.

// (c) Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.

// Version: 2.1.310
import { HKo } from './chunk-wc1zcry1.js';
import { Fe } from './chunk-a2y39866.js';

let nativeAudioAddon = null;
let hasAttemptedAudioLoad = false;

function loadNativeAudioAddon() {
  if (hasAttemptedAudioLoad) return nativeAudioAddon;
  hasAttemptedAudioLoad = true;
  // Preserve the extracted 2.1.310 linux-x64 build target.
  const targetPlatform = 'linux';
  if (targetPlatform !== 'darwin' && targetPlatform !== 'linux' && targetPlatform !== 'win32') return null;
  try {
    nativeAudioAddon = HKo();
    return nativeAudioAddon;
  } catch {}

  const architecture = 'x64-' + targetPlatform;
  const candidates = (targetPlatform === 'linux' ? [architecture, architecture + '-musl'] : [architecture])
    .flatMap(name => ['./vendor/audio-capture/' + name + '/audio-capture.node', '../audio-capture/' + name + '/audio-capture.node']);
  for (const candidate of candidates) {
    try {
      nativeAudioAddon = Fe(candidate);
      return nativeAudioAddon;
    } catch {}
  }
  return null;
}

function isNativeAudioAvailable() { return loadNativeAudioAddon() !== null; }
function startNativeRecording(device, onData) {
  const addon = loadNativeAudioAddon();
  if (!addon) return false;
  return addon.startRecording(device, onData);
}
function stopNativeRecording() {
  const addon = loadNativeAudioAddon();
  if (!addon) return;
  addon.stopRecording();
}
function isNativeRecordingActive() {
  const addon = loadNativeAudioAddon();
  if (!addon) return false;
  return addon.isRecording();
}
function startNativePlayback(format, options) {
  const addon = loadNativeAudioAddon();
  if (!addon) return false;
  return addon.startPlayback(format, options);
}
function writeNativePlaybackData(data) {
  const addon = loadNativeAudioAddon();
  if (!addon) return;
  addon.writePlaybackData(data);
}
function stopNativePlayback() {
  const addon = loadNativeAudioAddon();
  if (!addon) return;
  addon.stopPlayback();
}
function isNativePlaying() {
  const addon = loadNativeAudioAddon();
  if (!addon) return false;
  return addon.isPlaying();
}
function microphoneAuthorizationStatus() {
  const addon = loadNativeAudioAddon();
  if (!addon || !addon.microphoneAuthorizationStatus) return 0;
  return addon.microphoneAuthorizationStatus();
}

export {
  isNativeAudioAvailable,
  isNativePlaying,
  isNativeRecordingActive,
  microphoneAuthorizationStatus,
  startNativePlayback,
  startNativeRecording,
  stopNativePlayback,
  stopNativeRecording,
  writeNativePlaybackData,
};

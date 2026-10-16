"use strict";
/* dsh-android DIAGNOSTIC SHIM -- NOT a production fix.
 * The real node-addon-require-builtin is a Node-internals prober that walks
 * machine code / resolves private V8 symbols, and upstream publishes NO
 * android-arm64 implementation (see docs/support-matrix.md).
 * This shim substitutes ordinary require(), which only reaches `internal/...`
 * when node is started with --expose-internals. Used purely to find out which
 * OTHER native modules block dsh on Android.
 */
function requireBuiltin(moduleId) {
  return require(moduleId);
}
function isAllowedInternalId() { return true; }
function getBindingInfo() {
  return {
    product: "require-builtin",
    backend: "shim",
    platform: process.platform,
    arch: process.arch,
    abi: process.versions.modules,
    shimmed: true,
  };
}
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };

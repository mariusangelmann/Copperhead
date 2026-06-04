// Death-pact contract between the main Copperhead process and the
// :guardian sidecar process. The guardian links to death on a binder
// owned by the main process; when main dies for ANY reason (JVM
// exception, native SIGSEGV, SIGKILL, OOM-killer) the kernel binder
// driver notifies the guardian, which then ends the cellular leg.
package com.copperhead.gateway.guardian;

interface IGuardian {
    // Hand the guardian an anchor binder owned by the main process.
    // The guardian linkToDeath's a recipient on it.
    void startWatching(IBinder anchor);

    // Toggle whether Copperhead is currently bridging a call. The
    // death recipient only ends a call when this is true at the
    // moment of death — so an unrelated crash with no active bridge
    // is a no-op (we don't want to terminate the user's own calls).
    void setHasActiveCall(boolean active);
}

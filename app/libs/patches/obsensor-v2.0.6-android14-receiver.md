# Orbbec v2.0.6 Android 14 receiver patch

- Upstream: `orbbec/OrbbecSDK-Android-Wrapper`, tag `v2.0.6`
- Original AAR SHA-256: `4A9B6D3297E2E60A9CFB5E7C2E06793F41CB12C7A6357B54ACB63561CA3AF9BB`
- Patched AAR SHA-256: `F20073409C3A63011E5158E59829EA8522A68374D59547A6AFFD7614E79330B1`

Only `Enumerator.java` was recompiled and its four generated class files were
replaced. Native libraries, resources, manifests, and every other Java class
remain byte-for-byte identical to the upstream AAR.

The upstream USB permission receiver registration:

```java
context.registerReceiver(receiver, intentFilter);
```

was replaced with:

```java
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
} else {
    context.registerReceiver(receiver, intentFilter);
}
```

This prevents Android 14+ from throwing `SecurityException` when the SDK requests
USB permission after an Orbbec device attach. `OrbbecVendorPatchTests` pins the
reviewed AAR hash so an unreviewed binary change fails the unit-test gate.

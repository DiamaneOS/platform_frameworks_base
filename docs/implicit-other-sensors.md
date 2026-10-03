# Opting out of the implicit physical-sensor request

DiamaneOS supports this downstream marker in the **base APK's application
metadata**:

```xml
<meta-data android:name="de.diamaneos.permission.NO_IMPLICIT_OTHER_SENSORS"
    android:value="true" />
```

It suppresses only GrapheneOS's compatibility insertion of `OTHER_SENSORS` for
apps with code. An absent marker, Boolean false or a value of another type retains
existing behavior. An effective explicit `uses-permission` request is unchanged.
This does not alter the permission's definition, grant additional authority,
change signing identity, or require a package-name exception. Other Android bases
may ignore the metadata; it is not a standard Android or upstream GrapheneOS API.

An app that opts out and does not explicitly request `OTHER_SENSORS` has no such
request for the permission manager to grant. The pinned grant-policy paths operate
on requested permissions. For separate app UIDs, reconciliation trims permissions
no longer requested by any package sharing that UID, including prior fixed grants.
This is a source expectation, not a runtime acceptance result. A shared UID or an
explicit request from another package must not be treated as having opted out.

Deploy the framework behavior together with the app metadata. Verify the active
APK: a retained data-partition app update can shadow the system APK. Split-only
metadata arrives after base validation and cannot undo an inserted request.
Check both fresh installation and an upgrade from granted state, including a
system-fixed grant; verify persistence across reboot and relevant users. Test
absent/false/wrong-type markers and explicit requests to protect compatibility.

The IMS network broker, Wi-Fi observer and call-audio bridge use no physical
sensor API. Their radio/network/audio permissions remain separate. Sensor access
used by Telecom, the radio implementation and other system components is unchanged.
This does not disable call proximity handling or prove emergency-call behavior.

#!/usr/bin/env nu

# Create the release signing key, once, and print how to hand it to CI.
#
# Every update has to be signed with the same key, so keep the keystore
# and its password somewhere safe outside the repository. keytool asks for
# the password itself, so it never lands in the shell history. Run inside
# the devShell, which provides keytool:
#
#     nix develop --command nu scripts/release-key.nu
def main [
    out: path = "~/.android/hexboard-release.jks" # where to write the keystore
    --alias: string = "hexboard"                   # the key's alias in it
] {
    let out = $out | path expand
    if ($out | path exists) {
        error make {msg: $"($out) exists; refusing to overwrite a signing key"}
    }
    mkdir ($out | path dirname)
    ^keytool -genkeypair -keystore $out -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Hexboard"
    print ""
    print $"Wrote ($out). Give it to CI as repository secrets:"
    print $"  open --raw ($out) | encode base64 | gh secret set HEXBOARD_KEYSTORE_BASE64"
    print "  gh secret set HEXBOARD_KEYSTORE_PASSWORD"
    print $"  gh secret set HEXBOARD_KEY_ALIAS --body ($alias)"
}

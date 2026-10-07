{
  description = "hexboard – hex grid keyboard for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # SDK packages track Google's repository closely, which androidenv does
    # not. Its own nixpkgs is only used for fetchers, so follow ours.
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = {
    nixpkgs,
    android-nixpkgs,
    ...
  }: let
    forAllSystems = nixpkgs.lib.genAttrs ["x86_64-linux" "aarch64-linux"];
    # Keep in sync with compileSdk and buildToolsVersion in app/build.gradle.kts.
    # The SDK lives in the store and is read-only, so AGP cannot download a
    # version that is missing here; it fails instead.
    buildTools = "36.1.0";
  in {
    devShells = forAllSystems (system: let
      pkgs = nixpkgs.legacyPackages.${system};
      jdk = pkgs.jdk21;
      sdk = android-nixpkgs.sdk.${system} (sdkPkgs:
        with sdkPkgs; [
          cmdline-tools-latest
          platform-tools
          build-tools-36-1-0
          platforms-android-36
        ]);
    in {
      default = pkgs.mkShell {
        nativeBuildInputs = [
          sdk
          jdk
          (pkgs.gradle_9.override {java = jdk;})
        ];
        shellHook = ''
          export JAVA_HOME="${jdk}"
          export ANDROID_HOME="${sdk}/share/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          # Gradle fetches a dynamically linked aapt2 from Maven, which only
          # runs on NixOS through nix-ld. Use the SDK's own binary instead.
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildTools}/aapt2"
        '';
      };
    });
  };
}

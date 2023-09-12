{
  description = "Flake for dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/master";
    flake-utils.url = "github:numtide/flake-utils";
    sbtix.url = "github:natural-transformation/sbtix";
  };

  outputs = { self, nixpkgs, flake-utils, sbtix, mach-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        sbt-overlay = self: super: {
          sbt = super.sbt.override { jre = super.jdk; };
        };
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ sbt-overlay ];
        };
        sbtixPkg = import sbtix { inherit pkgs; };
      in {
        devShell = pkgs.mkShell {
          buildInputs = with pkgs; [
            sbt
            sbtixPkg
            jdk # currently openjdk 19
            coursier
          ];

          # environment variables go here
        };
      });
}

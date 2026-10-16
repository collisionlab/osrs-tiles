{
  description = "OSRS map tile renderer and pipeline for tiles.collisionlab.world";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk21_headless
          pkgs.gradle
          pkgs.python3
          pkgs.rclone
          pkgs.jq
        ];
      };

      formatter.${system} = pkgs.nixpkgs-fmt;
    };
}

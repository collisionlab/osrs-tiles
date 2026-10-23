{
  description = "OSRS map tile renderer and pipeline for tiles.collisionlab.world";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
  # The plugin's collision map generator (CollisionMapDumper.java), run on the same cache as the
  # tiles. Pinned in flake.lock, which is part of the tiles version.
  inputs.tooling = {
    url = "github:osrs-pathfinding/shortest-path-tooling";
    flake = false;
  };

  outputs = { self, nixpkgs, tooling }:
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
        COLLISION_DUMPER_SRC = "${tooling}/collision-map-update";
      };

      formatter.${system} = pkgs.nixpkgs-fmt;
    };
}

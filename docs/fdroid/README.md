# Tessera on F-Droid

F-Droid builds apps from source on its own servers and signs them with its own key, so the
F-Droid build and the GitHub/Obtainium build can't update over each other (uninstall one to
switch).

What's in this repository for it:

- `fastlane/metadata/android/en-US/`: the store listing (title, descriptions, changelog per
  version code, icon, screenshots). F-Droid reads it from the tagged commit.
- `docs/fdroid/com.daryan.tessera.yml`: a draft of the build recipe, for F-Droid's
  `fdroiddata` repository. It builds the native parts the way CI does (`scripts/`), then the APK
  unsigned (`-Ptessera.unsigned`), for F-Droid to sign.

To submit:

1. Fork https://gitlab.com/fdroid/fdroiddata on GitLab.
2. Copy `com.daryan.tessera.yml` to `metadata/com.daryan.tessera.yml` in the fork.
3. Optionally test it with `fdroid build -v -l com.daryan.tessera` (fdroidserver), or let the
   merge request's CI run it.
4. Open a merge request ("New app: Tessera"). Reviewers usually ask about:
   - downloads during the build (Mesa, libusb, PRoot's sources, the theme archives,
     Debian packages): they may want them as pinned `srclibs` or checksummed;
   - the Termux libraries from JitPack;
   - anti-features: Claude Code (NonFreeAdd) is declared; they may add others.

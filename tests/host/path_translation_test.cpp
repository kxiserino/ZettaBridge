// Guest system paths map into the sysroot, which holds only the 32-bit binaries. Anything the
// sysroot does not have - fonts above all - must fall through to the device's own file, or a
// guest can load its libraries and still be unable to draw a single letter (Flutter, 2026-09-19).
#include <cstdio>
#include <filesystem>
#include <string>

#include "check.h"
#include "zb/process.h"

namespace fs = std::filesystem;

int main() {
    const fs::path root = fs::temp_directory_path() / "zb-path-translation-test";
    fs::remove_all(root);
    fs::create_directories(root / "system" / "lib");
    std::FILE* library = std::fopen((root / "system" / "lib" / "libc.so").c_str(), "w");
    CHECK(library != nullptr);
    std::fputs("not a real library", library);
    std::fclose(library);

    zb::Process process;
    process.set_sysroot(root.string());

    // A file the sysroot has: the guest gets the sysroot copy, never the device's own.
    CHECK(process.translate_path("/system/lib/libc.so") == (root / "system/lib/libc.so").string());
    // The APEX bionic path collapses onto the same copy.
    CHECK(process.translate_path("/apex/com.android.runtime/lib/bionic/libc.so") ==
          (root / "system/lib/libc.so").string());

    // A file the sysroot does not have: the guest gets the device path unchanged. Fonts are the
    // reason this rule exists; they are architecture-independent and live outside the sysroot.
    CHECK(process.translate_path("/system/fonts/Roboto-Regular.ttf") ==
          std::string("/system/fonts/Roboto-Regular.ttf"));
    CHECK(process.translate_path("/system/etc/fonts.xml") == std::string("/system/etc/fonts.xml"));

    // Paths outside the mapped prefixes are never touched.
    CHECK(process.translate_path("/data/data/com.example/files/thing.ttf") ==
          std::string("/data/data/com.example/files/thing.ttf"));

    // ClassLoader.findLibrary returns a host proxy to ART. A guest dlopen of that same
    // path must resolve to its ARM32 counterpart, including Android's /data/user alias.
    fs::create_directories(root / "plugin/proxy");
    fs::create_directories(root / "plugin/lib");
    const fs::path proxy = root / "plugin/proxy/libgame.so";
    library = std::fopen(proxy.c_str(), "w");
    CHECK(library != nullptr);
    std::fputs("host proxy", library);
    std::fclose(library);
    fs::create_directory_symlink(root / "plugin", root / "alias");
    CHECK(process.translate_path(proxy.c_str()) == proxy.string());
    process.set_plugin_root(fs::canonical(root / "plugin").string());
    const std::string guest = (fs::canonical(root / "plugin") / "lib/libgame.so").string();
    CHECK(process.translate_path(proxy.c_str()) == guest);
    CHECK(process.translate_path((root / "alias/proxy/libgame.so").c_str()) == guest);
    CHECK(process.translate_path((root / "plugin/lib/libgame.so").c_str()) ==
          (root / "plugin/lib/libgame.so").string());
    // No prefix collisions, non-library redirects, or redirects outside this plugin.
    fs::create_directories(root / "other/proxy");
    fs::copy_file(proxy, root / "other/proxy/libgame.so");
    CHECK(process.translate_path((root / "other/proxy/libgame.so").c_str()) ==
          (root / "other/proxy/libgame.so").string());
    fs::create_directories(root / "plugin/proxy-other");
    fs::copy_file(proxy, root / "plugin/proxy-other/libgame.so");
    CHECK(process.translate_path((root / "plugin/proxy-other/libgame.so").c_str()) ==
          (root / "plugin/proxy-other/libgame.so").string());
    fs::create_symlink(root / "other/proxy/libgame.so", root / "plugin/proxy/outside.so");
    CHECK(process.translate_path((root / "plugin/proxy/outside.so").c_str()) ==
          (root / "plugin/proxy/outside.so").string());
    fs::copy_file(proxy, root / "plugin/proxy/report.txt");
    CHECK(process.translate_path((root / "plugin/proxy/report.txt").c_str()) ==
          (root / "plugin/proxy/report.txt").string());

    fs::remove_all(root);
    std::puts("path_translation_test PASS");
    return 0;
}

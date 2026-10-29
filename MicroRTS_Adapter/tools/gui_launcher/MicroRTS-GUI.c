/*
 * MicroRTS-GUI.exe: starts MicroRTS-GUI.jar, which must sit in the same
 * folder. It runs Java from that folder, so maps/, bots/ and lib/ resolve
 * next to the jar however the exe is started. Java comes from JAVA_HOME
 * when set, otherwise javaw.exe on the PATH. Built by build_gui.cmd:
 *   gcc -O2 -municode -mwindows -o MicroRTS-GUI.exe tools/gui_launcher/MicroRTS-GUI.c
 */
#include <windows.h>
#include <wchar.h>

static const wchar_t *TITLE = L"MicroRTS GUI";

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
    (void) instance; (void) previous; (void) show;

    wchar_t dir[MAX_PATH];
    DWORD len = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) {
        MessageBoxW(NULL, L"Could not find the launcher's own folder.", TITLE, MB_ICONERROR);
        return 1;
    }
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash != NULL) {
        *slash = L'\0';
    }

    wchar_t jar[MAX_PATH + 32];
    _snwprintf(jar, sizeof jar / sizeof jar[0], L"%ls\\MicroRTS-GUI.jar", dir);
    jar[sizeof jar / sizeof jar[0] - 1] = L'\0';
    if (GetFileAttributesW(jar) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"MicroRTS-GUI.jar was not found next to MicroRTS-GUI.exe.\n"
                          L"Run build_gui.cmd to build it.", TITLE, MB_ICONERROR);
        return 1;
    }

    wchar_t java[MAX_PATH + 32] = L"javaw.exe";
    wchar_t home[MAX_PATH];
    DWORD homeLen = GetEnvironmentVariableW(L"JAVA_HOME", home, MAX_PATH);
    if (homeLen > 0 && homeLen < MAX_PATH) {
        wchar_t candidate[MAX_PATH + 32];
        _snwprintf(candidate, sizeof candidate / sizeof candidate[0], L"%ls\\bin\\javaw.exe", home);
        candidate[sizeof candidate / sizeof candidate[0] - 1] = L'\0';
        if (GetFileAttributesW(candidate) != INVALID_FILE_ATTRIBUTES) {
            wcscpy(java, candidate);
        }
    }

    /* Extra arguments given to the exe are passed on to the GUI. */
    wchar_t command[4 * MAX_PATH + 32768];
    _snwprintf(command, sizeof command / sizeof command[0], L"\"%ls\" -jar \"%ls\" %ls", java, jar,
               args != NULL ? args : L"");
    command[sizeof command / sizeof command[0] - 1] = L'\0';

    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&startup, sizeof startup);
    startup.cb = sizeof startup;
    ZeroMemory(&process, sizeof process);
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, 0, NULL, dir, &startup, &process)) {
        MessageBoxW(NULL, L"Could not start Java (javaw.exe).\n"
                          L"Install Java 17 or newer, or set JAVA_HOME to its folder.", TITLE, MB_ICONERROR);
        return 1;
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}

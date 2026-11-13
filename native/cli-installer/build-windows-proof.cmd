@echo off
setlocal
if "%~1"=="" exit /b 1
if not defined VCToolsInstallDir (
    for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        call "%%i\VC\Auxiliary\Build\vcvars64.bat" || exit /b 1
    )
)
pushd "%~dp1" || exit /b 1
cl /nologo /std:c17 /O2 /W4 /WX /MT /guard:cf /DINSTALLER_ROOT_PROOF "%~dp0windows-root.c" "%~dp0windows-root-proof.c" /Fe"%~f1" /link advapi32.lib ntdll.lib /Brepro /DYNAMICBASE /NXCOMPAT /guard:cf
set "result=%ERRORLEVEL%"
popd
exit /b %result%

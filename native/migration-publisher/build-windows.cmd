@echo off
setlocal
if "%~1"=="" exit /b 1
if not defined VCToolsInstallDir (
    for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        call "%%i\VC\Auxiliary\Build\vcvars64.bat" || exit /b 1
    )
)
cl /nologo /std:c17 /O2 /W4 /WX /MT /guard:cf "%~dp0windows.c" /Fo"%~dpn1.obj" /Fe"%~f1" /link /Brepro /DYNAMICBASE /NXCOMPAT /guard:cf
exit /b %ERRORLEVEL%

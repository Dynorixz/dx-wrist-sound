# Prefer the caller's JDK; keep the author's relocated local toolchain as a fallback.
function Resolve-DXJavaHome {
    $candidates = @($env:JAVA_HOME, 'D:\Editors\AndroidToolchain\OpenJDK', 'E:\Editors\AndroidToolchain\OpenJDK')
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
            return $candidate
        }
    }
    throw 'Set JAVA_HOME to an installed JDK 17 before building.'
}

param(
    [ValidateSet('Run', 'Maven')][string]$Mode = 'Run',
    [switch]$ValidateOnly,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments
)

# Resolve ferramentas existentes sem instalar pacotes ou mudar o PATH do Windows.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot

function Find-ProjectJdk {
    $taskCandidates = @()
    if ($env:JAVA_HOME) { $taskCandidates += $env:JAVA_HOME }
    foreach ($taskParent in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft")) {
        if (Test-Path -LiteralPath $taskParent) {
            $taskCandidates += @(Get-ChildItem -LiteralPath $taskParent -Directory | Sort-Object LastWriteTime -Descending | ForEach-Object { $_.FullName })
        }
    }
    $taskCompiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($taskCompiler -and $taskCompiler.Source -notmatch '\\javapath\\') {
        $taskCandidates += Split-Path -Parent (Split-Path -Parent $taskCompiler.Source)
    }
    foreach ($taskCandidate in ($taskCandidates | Select-Object -Unique)) {
        $taskCompilerPath = Join-Path $taskCandidate 'bin\javac.exe'
        if ((Test-Path -LiteralPath $taskCompilerPath) -and (Test-Path -LiteralPath (Join-Path $taskCandidate 'bin\java.exe'))) {
            $taskVersion = & $taskCompilerPath --version
            if ($LASTEXITCODE -eq 0 -and "$taskVersion" -match '^javac\s+(\d+)' -and [int]$Matches[1] -ge 21) { return $taskCandidate }
        }
    }
    throw 'JDK 21 ou superior nao encontrado. Instale um JDK ou configure JAVA_HOME para sua pasta.'
}

function Find-ProjectMaven {
    $taskCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($taskCommand) { return $taskCommand.Source }
    foreach ($taskMavenRoot in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($taskMavenRoot -and (Test-Path -LiteralPath (Join-Path $taskMavenRoot 'bin\mvn.cmd'))) { return Join-Path $taskMavenRoot 'bin\mvn.cmd' }
    }
    $taskCache = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
    if (Test-Path -LiteralPath $taskCache) {
        $taskCached = Get-ChildItem -LiteralPath $taskCache -Recurse -Filter mvn.cmd | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($taskCached) { return $taskCached.FullName }
    }
    throw 'Maven nao encontrado no PATH, MAVEN_HOME ou cache local. Use executar.cmd para abrir a aplicacao; para Maven, instale-o ou configure MAVEN_HOME.'
}

try {
    $taskJdk = Find-ProjectJdk
    $env:JAVA_HOME = $taskJdk
    $env:PATH = (Join-Path $taskJdk 'bin') + ';' + $env:PATH
    Push-Location -LiteralPath $taskRoot
    try {
        if ($Mode -eq 'Maven') {
            $taskMaven = Find-ProjectMaven
            if ($ValidateOnly) { Write-Output "Java: $taskJdk"; Write-Output "Maven: $taskMaven"; exit 0 }
            & $taskMaven @MavenArguments
            exit $LASTEXITCODE
        }
        $taskJar = Join-Path $taskRoot 'target\sicxe-simulator-1.0-SNAPSHOT.jar'
        $taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\main\java') -Recurse -Filter '*.java')
        $taskRebuild = -not (Test-Path -LiteralPath $taskJar)
        if (-not $taskRebuild) {
            $taskJarDate = (Get-Item -LiteralPath $taskJar).LastWriteTime
            $taskRebuild = @($taskSources | Where-Object LastWriteTime -gt $taskJarDate).Count -gt 0
        }
        if ($ValidateOnly) { Write-Output "Java: $taskJdk"; Write-Output "Programa: $taskJar"; Write-Output "Recompilar: $taskRebuild"; exit 0 }
        if ($taskRebuild) {
            $taskClasses = Join-Path $taskRoot 'target\classes'
            New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
            $taskPaths = @($taskSources | ForEach-Object { $_.FullName })
            & (Join-Path $taskJdk 'bin\javac.exe') --release 21 -encoding UTF-8 -d $taskClasses @taskPaths
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            & (Join-Path $taskJdk 'bin\java.exe') -cp $taskClasses Main
        } else {
            & (Join-Path $taskJdk 'bin\java.exe') -jar $taskJar
        }
        exit $LASTEXITCODE
    } finally { Pop-Location }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

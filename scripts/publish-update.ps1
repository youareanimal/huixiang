param(
    [string]$PiHost = 'pi.animal26.site',
    [string]$Python = 'python',
    [string]$ReleaseNotes = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $projectRoot
try {
    $prepareArgs = @('./scripts/prepare-update.py')
    if ($ReleaseNotes) { $prepareArgs += @('--notes', $ReleaseNotes) }
    & $Python @prepareArgs
    if ($LASTEXITCODE -ne 0) { throw 'Preparing update assets failed.' }
    $stage = Join-Path $projectRoot 'dist/update-site'
    $remoteRoot = '/home/maxin/huixiang-update-host'
    $stamp = [Guid]::NewGuid().ToString('N')
    $remoteStage = "$remoteRoot/release-$stamp"
    & ssh -o BatchMode=yes $PiHost "mkdir -p '$remoteStage'"
    if ($LASTEXITCODE -ne 0) { throw 'Creating upload directory failed.' }
    foreach ($file in Get-ChildItem -LiteralPath $stage -File) {
        & scp -o BatchMode=yes $file.FullName "${PiHost}:$remoteStage/"
        if ($LASTEXITCODE -ne 0) { throw "Uploading $($file.Name) failed." }
    }
    # Wrangler uses its existing standard login. It uploads assets before switching the deployed release.
    $deploy = "cd '$remoteRoot' && node /home/maxin/.npm/_npx/32026684e21afda6/node_modules/wrangler/bin/wrangler.js pages deploy '$remoteStage' --project-name huixiang-updates --branch main --commit-dirty=true"
    & ssh -o BatchMode=yes $PiHost $deploy
    if ($LASTEXITCODE -ne 0) { throw 'Publishing update assets failed.' }
    $feed = Get-Content -LiteralPath (Join-Path $stage 'latest.json') -Raw | ConvertFrom-Json
    $arm64 = Split-Path $feed.artifacts.'arm64-v8a'.url -Leaf
    # Keep the familiar private cloud-drive download entry as well.
    & ssh -o BatchMode=yes $PiHost "cp -n '$remoteStage/$arm64' '/srv/openlist/share/$arm64'"
    if ($LASTEXITCODE -ne 0) { throw 'Cloud-drive copy failed.' }
    Write-Output 'Published: https://huixiang-updates.pages.dev/latest.json'
} finally { Pop-Location }

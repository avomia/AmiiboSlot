$ErrorActionPreference = 'Stop'
$taskManager = 'C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe'
$taskAdb = 'C:\Program Files\Netease\MuMuPlayer\nx_main\adb.exe'
$taskPython = 'C:\Users\ebelk\Downloads\ProxSpace\ProxSpace\msys2\mingw64\bin\python.exe'
foreach ($taskPath in @($taskManager, $taskAdb, $taskPython)) {
    if (-not (Test-Path -LiteralPath $taskPath)) { throw "Не найдено: $taskPath" }
}
$taskInfo = & $taskManager adb -v 0 -c connect | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $taskInfo.adb_port) { throw 'Запустите MuMuPlayer и повторите.' }
$taskSerial = '{0}:{1}' -f $taskInfo.adb_host,$taskInfo.adb_port
& $taskAdb connect $taskSerial
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подключить adb к MuMu.' }
& $taskAdb -s $taskSerial reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw 'Не удалось настроить канал MuMu → ПК.' }
Write-Host 'USB-мост готов. Оставьте это окно открытым, затем в APK нажмите «Подключить через ПК / MuMu».'
& $taskPython "$PSScriptRoot\pc_bridge.py" COM6

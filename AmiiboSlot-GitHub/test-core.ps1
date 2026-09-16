$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskClasses = Join-Path $taskRoot 'build/core-tests'
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskSource = Join-Path $taskRoot 'app/src/main/java/app/amiiboslot'
& javac -encoding UTF-8 -d $taskClasses "$taskSource/Amiibo.java" "$taskSource/AmiiboCrypto.java" "$taskSource/Frame.java" "$taskSource/Chameleon.java" "$taskRoot/tests/CoreTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& java -cp $taskClasses app.amiiboslot.CoreTest "$taskRoot/build/crypto-fixtures"
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }

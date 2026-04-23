$path = Join-Path $PSScriptRoot "..\..\..\..\..\Users\stalk\.cursor\projects\d-Web-CoopTrumdu-game1-ImFriend\agent-tools\b2926b64-27e5-4845-bb4b-199f7e829c8e.txt"
$path = "C:\Users\stalk\.cursor\projects\d-Web-CoopTrumdu-game1-ImFriend\agent-tools\b2926b64-27e5-4845-bb4b-199f7e829c8e.txt"
$j = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($svc in $j.docs.services) {
  foreach ($r in $svc.routes) {
    Write-Output ("{0} {1}" -f $r.method.ToUpper(), $r.path)
  }
}

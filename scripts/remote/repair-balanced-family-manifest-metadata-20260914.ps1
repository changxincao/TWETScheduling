#requires -Version 7.0
param([Parameter(Mandatory = $true)][ValidateSet('arc', 'cluster')][string]$HostKind)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$suite = Join-Path $repo 'experiment-suite/family-count-balanced-20260914'
$stage = Join-Path $suite "manifests/$HostKind"
$index = @{}
foreach ($row in Import-Csv -LiteralPath (Join-Path $suite 'instances/instances.tsv') -Delimiter "`t") {
    $index[$row.instance.Replace('\', '/')] = $row
}
$utf8 = [Text.UTF8Encoding]::new($false)
foreach ($name in @('seed.tsv', 'solve.tsv')) {
    $path = Join-Path $stage $name
    $lines = [IO.File]::ReadAllLines($path)
    $columns = $lines[0].Split("`t")
    $positions = @{}
    for ($i = 0; $i -lt $columns.Length; $i++) { $positions[$columns[$i]] = $i }
    $expected = if ($name -eq 'seed.tsv') { 270 } else { 540 }
    if ($lines.Length -ne $expected + 1) { throw "Unexpected row count: $path" }
    for ($i = 1; $i -lt $lines.Length; $i++) {
        $cells = $lines[$i].Split("`t")
        $argsText = $cells[$positions['args']]
        if ($argsText -notmatch '--instance="[^"]*/instances/family-count-balanced-20260914/(?<rel>data/[^"]+)"') {
            throw "Unexpected instance argument at ${path}:$($i + 1)"
        }
        $relative = $Matches['rel']
        $row = $index[$relative]
        if ($null -eq $row) { throw "Instance missing from index: $relative" }
        if ($cells[$positions['runId']] -notmatch [regex]::Escape("$($row.taskSetId)-m$($row.machines)-family-$($row.scaleLevel)-w$($row.windowLevel)-f3")) {
            throw "Run ID and indexed input disagree at ${path}:$($i + 1)"
        }
        foreach ($field in @('taskSetId', 'size', 'machines', 'scaleLevel', 'windowLevel')) {
            $prior = $cells[$positions[$field]]
            $value = [string]$row.$field
            if ($prior -and $prior -ne $value) { throw "Conflicting $field at ${path}:$($i + 1)" }
            $cells[$positions[$field]] = $value
        }
        $lines[$i] = $cells -join "`t"
    }
    [IO.File]::WriteAllLines($path, $lines, $utf8)
    "$HostKind/$name metadata checked: $expected rows"
}

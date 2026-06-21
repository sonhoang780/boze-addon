$prompt = Get-Content "$PSScriptRoot\automace_prompt.txt" -Raw
Set-Location $PSScriptRoot
$prompt | claude

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('status', 'start', 'stop')]
    [string]$Action,

    [string]$StackName = 'pretriage-demo',

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Profile,

    [ValidateNotNullOrEmpty()]
    [string]$Region = 'us-east-1',

    [switch]$UpdateCloudflareDns
)

$ErrorActionPreference = 'Stop'

if ($Region -ne 'us-east-1' -and $env:PRETRIAGE_CONFIRMED_AWS_REGION -ne $Region) {
    throw "Region '$Region' is not the default. Confirm the project's selected Region in AWS Settings, then set PRETRIAGE_CONFIRMED_AWS_REGION to that exact Region before running this helper."
}
if ($UpdateCloudflareDns -and $Action -ne 'start') {
    throw '-UpdateCloudflareDns is only valid with -Action start.'
}

function Invoke-AwsCli {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $result = & aws --no-cli-pager @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $message = ($result | ForEach-Object { [string]$_ }) -join ' '
        throw "AWS CLI command failed (exit $LASTEXITCODE): $message"
    }
    return ($result | Out-String).Trim()
}

function Get-StackInstanceId {
    $json = Invoke-AwsCli -Arguments @(
        'cloudformation', 'describe-stacks',
        '--stack-name', $StackName,
        '--profile', $Profile,
        '--region', $Region,
        '--output', 'json'
    )
    $stackResponse = $json | ConvertFrom-Json
    $stack = @($stackResponse.Stacks) | Select-Object -First 1
    if ($null -eq $stack) {
        throw "CloudFormation stack '$StackName' was not found."
    }

    $instanceOutput = @($stack.Outputs | Where-Object { $_.OutputKey -eq 'InstanceId' }) | Select-Object -First 1
    if ($null -eq $instanceOutput -or [string]::IsNullOrWhiteSpace($instanceOutput.OutputValue)) {
        throw "CloudFormation stack '$StackName' has no non-empty InstanceId output."
    }
    return [string]$instanceOutput.OutputValue
}

function Get-Instance {
    param([Parameter(Mandatory = $true)][string]$InstanceId)

    $json = Invoke-AwsCli -Arguments @(
        'ec2', 'describe-instances',
        '--instance-ids', $InstanceId,
        '--profile', $Profile,
        '--region', $Region,
        '--query', 'Reservations[0].Instances[0].{State:State.Name,PublicIp:PublicIpAddress}',
        '--output', 'json'
    )
    $instance = $json | ConvertFrom-Json
    if ($null -eq $instance -or [string]::IsNullOrWhiteSpace($instance.State)) {
        throw "EC2 instance '$InstanceId' was not found or has no state."
    }
    return $instance
}

function Wait-ForInstance {
    param(
        [Parameter(Mandatory = $true)][string]$InstanceId,
        [Parameter(Mandatory = $true)][ValidateSet('instance-running', 'instance-stopped')][string]$State
    )

    [void](Invoke-AwsCli -Arguments @(
        'ec2', 'wait', $State,
        '--instance-ids', $InstanceId,
        '--profile', $Profile,
        '--region', $Region
    ))
}

function Wait-ForInstanceStatusOk {
    param([Parameter(Mandatory = $true)][string]$InstanceId)

    [void](Invoke-AwsCli -Arguments @(
        'ec2', 'wait', 'instance-status-ok',
        '--instance-ids', $InstanceId,
        '--profile', $Profile,
        '--region', $Region
    ))
}

function Update-CloudflareApiRecord {
    [CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
    param([Parameter(Mandatory = $true)][string]$PublicIp)

    $token = $env:CLOUDFLARE_API_TOKEN
    $zoneId = $env:CLOUDFLARE_ZONE_ID
    if ([string]::IsNullOrWhiteSpace($token) -or [string]::IsNullOrWhiteSpace($zoneId)) {
        throw 'Set CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID before requesting a DNS update. The EC2 instance remains running and continues to incur charges.'
    }

    $name = 'api.pretriage.com.ar'
    $encodedName = [Uri]::EscapeDataString($name)
    $headers = @{ Authorization = "Bearer $token" }
    $listUri = "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records?type=A&name=$encodedName"
    try {
        $listResponse = Invoke-RestMethod -Method Get -Uri $listUri -Headers $headers
    }
    catch {
        throw 'Cloudflare DNS lookup failed; response details omitted.'
    }
    if (-not $listResponse.success) {
        throw 'Cloudflare DNS lookup returned an unsuccessful result; response details omitted. The EC2 instance remains running and continues to incur charges.'
    }

    $records = @($listResponse.result | Where-Object {
        $_.type -eq 'A' -and $_.name -eq $name
    })
    if ($records.Count -ne 1) {
        throw "Expected exactly one existing A record for '$name'; found $($records.Count). No record was changed. The EC2 instance remains running and continues to incur charges."
    }

    $record = $records[0]
    if ($record.content -eq $PublicIp -and $record.proxied -eq $true -and [int]$record.ttl -eq 1) {
        Write-Output "Cloudflare DNS is already set: $name -> $PublicIp (proxied)."
        return
    }

    $patchUri = "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$($record.id)"
    $body = @{
        content = $PublicIp
        proxied = $true
        ttl     = 1
    } | ConvertTo-Json -Compress

    if ($PSCmdlet.ShouldProcess("existing A record $name", "Set address to $PublicIp and enable Cloudflare proxy")) {
        try {
            $patchResponse = Invoke-RestMethod -Method Patch -Uri $patchUri -Headers $headers `
                -ContentType 'application/json' -Body $body
        }
        catch {
            throw 'Cloudflare DNS update failed; response details omitted. The EC2 instance remains running and continues to incur charges.'
        }
        if (-not $patchResponse.success -or $patchResponse.result.content -ne $PublicIp `
                -or $patchResponse.result.proxied -ne $true -or [int]$patchResponse.result.ttl -ne 1) {
            throw 'Cloudflare did not confirm the requested DNS update. The EC2 instance remains running and continues to incur charges.'
        }
        Write-Output "Cloudflare DNS updated: $name -> $PublicIp (proxied)."
    }
}

$instanceId = Get-StackInstanceId
$instance = Get-Instance -InstanceId $instanceId

switch ($Action) {
    'status' {
        Write-Output ("Stack: {0}; instance: {1}; state: {2}; public IP: {3}" -f `
            $StackName, $instanceId, $instance.State, $(if ($instance.PublicIp) { $instance.PublicIp } else { '<none>' }))
    }

    'start' {
        if ($instance.State -eq 'stopping') {
            Write-Output "Instance '$instanceId' is stopping; waiting for it to stop before starting."
            Wait-ForInstance -InstanceId $instanceId -State 'instance-stopped'
            $instance = Get-Instance -InstanceId $instanceId
        }

        $startApproved = $true
        if ($instance.State -eq 'stopped') {
            $startApproved = $PSCmdlet.ShouldProcess("EC2 instance $instanceId", "Start in Region $Region")
            if ($startApproved) {
                [void](Invoke-AwsCli -Arguments @(
                    'ec2', 'start-instances',
                    '--instance-ids', $instanceId,
                    '--profile', $Profile,
                    '--region', $Region,
                    '--output', 'json'
                ))
            }
        }
        elseif ($instance.State -notin @('pending', 'running')) {
            throw "Cannot start EC2 instance '$instanceId' from state '$($instance.State)'."
        }

        if (-not $startApproved) {
            Write-Output "What if: EC2 instance '$instanceId' would be started."
            break
        }

        Wait-ForInstance -InstanceId $instanceId -State 'instance-running'
        Wait-ForInstanceStatusOk -InstanceId $instanceId
        $instance = Get-Instance -InstanceId $instanceId
        Write-Output ("EC2 instance is ready: {0}; assigned fixed public IPv4: {1}" -f `
            $instanceId, $(if ($instance.PublicIp) { $instance.PublicIp } else { '<none>' }))

        if ($UpdateCloudflareDns) {
            if ([string]::IsNullOrWhiteSpace($instance.PublicIp)) {
                throw "EC2 instance '$instanceId' has no public IP, so Cloudflare DNS cannot be updated. The EC2 instance remains running and continues to incur charges."
            }
            Update-CloudflareApiRecord -PublicIp $instance.PublicIp
        }
        elseif ($instance.PublicIp) {
            Write-Output ("Keep the existing A record api.pretriage.com.ar pointed to the fixed public IPv4 {0}. No DNS change is needed after a normal stop/start." -f $instance.PublicIp)
        }
    }

    'stop' {
        if ($instance.State -eq 'stopping') {
            Write-Output "Instance '$instanceId' is already stopping; waiting for it to stop."
            Wait-ForInstance -InstanceId $instanceId -State 'instance-stopped'
        }
        elseif ($instance.State -eq 'running') {
            if ($PSCmdlet.ShouldProcess("EC2 instance $instanceId", "Stop without force in Region $Region")) {
                [void](Invoke-AwsCli -Arguments @(
                    'ec2', 'stop-instances',
                    '--instance-ids', $instanceId,
                    '--profile', $Profile,
                    '--region', $Region,
                    '--output', 'json'
                ))
                Wait-ForInstance -InstanceId $instanceId -State 'instance-stopped'
            }
        }
        elseif ($instance.State -eq 'pending') {
            Write-Output "Instance '$instanceId' is starting; waiting for it to run before stopping."
            Wait-ForInstance -InstanceId $instanceId -State 'instance-running'
            if ($PSCmdlet.ShouldProcess("EC2 instance $instanceId", "Stop without force in Region $Region")) {
                [void](Invoke-AwsCli -Arguments @(
                    'ec2', 'stop-instances',
                    '--instance-ids', $instanceId,
                    '--profile', $Profile,
                    '--region', $Region,
                    '--output', 'json'
                ))
                Wait-ForInstance -InstanceId $instanceId -State 'instance-stopped'
            }
        }
        elseif ($instance.State -ne 'stopped') {
            throw "Cannot stop EC2 instance '$instanceId' from state '$($instance.State)'."
        }

        $instance = Get-Instance -InstanceId $instanceId
        Write-Output ("EC2 instance state: {0}; public IP: {1}" -f `
            $instance.State, $(if ($instance.PublicIp) { $instance.PublicIp } else { '<none>' }))
    }
}

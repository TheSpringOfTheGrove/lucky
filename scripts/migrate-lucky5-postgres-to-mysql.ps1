[CmdletBinding()]
param(
    [long]$TenantId = 1,
    [long]$OwnerUserId = 1,
    [string]$OldCompose = 'D:\Projects\lucky5\docker-compose.yml',
    [string]$CurrentCompose = (Join-Path (Split-Path $PSScriptRoot -Parent) 'compose.yaml'),
    [string]$OldDatabase = 'lucky5',
    [string]$OldDatabaseUser = 'lucky5',
    [string]$CurrentDatabase = 'ruoyi-vue-pro',
    [string]$CurrentDatabaseUser = 'root',
    [string]$CurrentDatabasePassword = '123456',
    [switch]$SkipSchema
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$InvariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$SchemaFile = Join-Path $ProjectRoot 'sql\mysql\lucky5-business.sql'

function Get-RowValue {
    param([object]$Row, [object]$Mapping)
    if ($Mapping -is [scriptblock]) {
        return & $Mapping $Row
    }
    $property = $Row.PSObject.Properties[[string]$Mapping]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function ConvertTo-SqlLiteral {
    param([object]$Value)
    if ($null -eq $Value -or $Value -is [System.DBNull]) { return 'NULL' }
    if ($Value -is [bool]) { return $(if ($Value) { "b'1'" } else { "b'0'" }) }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or
        $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64] -or $Value -is [single] -or
        $Value -is [double] -or $Value -is [decimal]) {
        return [Convert]::ToString($Value, $InvariantCulture)
    }
    if ($Value -is [datetime]) {
        return "'$($Value.ToString('yyyy-MM-dd HH:mm:ss.ffffff', $InvariantCulture))'"
    }
    if ($Value -isnot [string]) {
        $Value = $Value | ConvertTo-Json -Compress -Depth 30
    }
    $text = [string]$Value
    if ($text -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}') {
        $timestamp = [datetimeoffset]::Parse($text, $InvariantCulture)
        return "'$($timestamp.ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss.ffffff', $InvariantCulture))'"
    }
    if ($text.Length -eq 0) { return "''" }
    $hex = [BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($text)).Replace('-', '')
    return "CONVERT(0x$hex USING utf8mb4)"
}

function New-MigrationSpec {
    param(
        [string]$Source,
        [string]$Target,
        [System.Collections.Specialized.OrderedDictionary]$Columns,
        [string]$CreatedAt = 'createdAt',
        [string]$UpdatedAt = 'updatedAt'
    )
    $Columns['user_id'] = { $OwnerUserId }
    $Columns['creator'] = { 'migration' }
    $Columns['create_time'] = $CreatedAt
    $Columns['updater'] = { 'migration' }
    $Columns['update_time'] = $UpdatedAt
    $Columns['deleted'] = { $false }
    $Columns['tenant_id'] = { $TenantId }
    return [pscustomobject]@{ Source = $Source; Target = $Target; Columns = $Columns }
}

$specs = @(
    (New-MigrationSpec 'SystemConfig' 'lucky5_config' ([ordered]@{
        room_name='roomName'; close_time='closeTime'; settle_delay='settleDelay'; min_deposit='minDeposit';
        max_deposit='maxDeposit'; announcement='announcement'; service_url='serviceUrl'; chat_url='chatUrl';
        upstream_url='url'; upstream_account='account'; market_password_encrypted='marketPasswordEncrypted';
        alert_value='alertValue'; boss_mode='bossMode'; play_type='playType'; use_proxy='useProxy'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'SystemState' 'lucky5_system_state' ([ordered]@{
        operator_username='operatorUsername'; expire_at='expireAt'; room_open='roomOpen'; online='online';
        chima_cleared_at='chimaClearedAt'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'MarketConnection' 'lucky5_market_connection' ([ordered]@{
        status='status'; line_url='lineUrl'; display_account='displayAccount'; balance='balance'; error='error';
        last_login_at='lastLoginAt'; last_sync_at='lastSyncAt'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'LinkConfig' 'lucky5_link_config' ([ordered]@{
        device_id='deviceId'; dealer_url='dealerUrl'; room_url='roomUrl'; short_url='shortUrl'; qr_mode='qrMode';
        short_url_mode='shortUrlMode'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'ChimaConfig' 'lucky5_chima_config' ([ordered]@{
        si_zi_xian='siZiXian'; san_zi_xian='sanZiXian'; er_zi_xian='erZiXian'; dan_zi_xian='danZiXian';
        si_ding_wei='siDingWei'; san_ding_wei='sanDingWei'; er_ding_wei='erDingWei'; yi_ding_wei='yiDingWei';
        yin_kui_max='yinKuiMax'; yin_kui_min='yinKuiMin'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'SwitchSetting' 'lucky5_switch_setting' ([ordered]@{
        setting_key='key'; label='label'; enabled='enabled'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'Integration' 'lucky5_integration' ([ordered]@{
        integration_key='key'; name='name'; account='account'; group_name='groupName'; status='status'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'Odd' 'lucky5_odd' ([ordered]@{
        code='id'; play='play'; item='item'; rate='rate'; secondary_rate='secondaryRate'; min_limit='minLimit';
        max_limit='maxLimit'; status='status'
    }) 'updatedAt' 'updatedAt'),
    (New-MigrationSpec 'Member' 'lucky5_member' ([ordered]@{
        id='id'; name='name'; balance='balance'; status='status'; partner='partner'; normal_rate='normalRate';
        lhh_rate='lhhRate'; tag='tag'; external_nickname='externalNickname'; total_bet='totalBet';
        profit_loss='profitLoss'; auto_proxy='autoProxy'; eat_enabled='eatEnabled'; searchable='searchable';
        open_id='openId'; fingerprint='fingerprint'; private_chat='privateChat'; web_only='webOnly';
        blue_whale_password='blueWhalePassword'; avatar='avatar'; flow_cleared_at='flowClearedAt'; version='version'
    })),
    (New-MigrationSpec 'AmountRecord' 'lucky5_amount_record' ([ordered]@{
        id='id'; member_id='memberId'; member_name='memberName'; type='type'; amount='amount'; status='status';
        remark='remark'; audited_at='auditedAt'; audited_by='auditedBy'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'Order' 'lucky5_order' ([ordered]@{
        id='id'; member_id='memberId'; member_name='memberName'; period='period'; content='content'; amount='amount';
        win='win'; status='status'; source='source'; delivery_mode='deliveryMode'; market_status='marketStatus';
        market_order_id='marketOrderId'; market_error='marketError'; market_attempts='marketAttempts';
        period_sequence='periodSequence'; version='version'; settled_at='settledAt'; cancelled_at='cancelledAt'
    })),
    (New-MigrationSpec 'BetItem' 'lucky5_bet_item' ([ordered]@{
        id='id'; order_id='orderId'; play='play'; selection='selection'; amount='amount'; odds='odds'; won='won'; payout='payout'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'Draw' 'lucky5_draw' ([ordered]@{
        period='period'; result='result'; big_small='bigSmall'; odd_even='oddEven'; dragon_tiger='dragonTiger';
        status='status'; settled_at='settledAt'
    }) 'settledAt' 'settledAt'),
    (New-MigrationSpec 'Issue' 'lucky5_issue' ([ordered]@{
        period='period'; status='status'; market_status='marketStatus'; remaining_seconds='remainingSeconds';
        server_time='serverTime'; next_period='nextPeriod'; opened_at='openedAt'; closed_at='closedAt';
        draw_time='drawTime'; draw_updated_at='drawUpdatedAt'; result='result'; source='source';
        raw_snapshot='rawSnapshot'; error='error'; settlement_started_at='settlementStartedAt'; settled_at='settledAt';
        order_sequence='orderSequence'
    })),
    (New-MigrationSpec 'IssueTransition' 'lucky5_issue_transition' ([ordered]@{
        legacy_id='id'; period='period'; from_status='fromStatus'; to_status='toStatus'; source='source'; detail='detail'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'FakeOrder' 'lucky5_preset_order' ([ordered]@{
        id='id'; member='member'; content='content'; enabled='enabled'
    })),
    (New-MigrationSpec 'QuickCommand' 'lucky5_quick_command' ([ordered]@{
        id='id'; label='label'; content='content'; sort='sort'; enabled='enabled'
    })),
    (New-MigrationSpec 'FollowOrder' 'lucky5_follow_order' ([ordered]@{
        id='id'; source='source'; target='target'; ratio='ratio'; enabled='enabled'
    })),
    (New-MigrationSpec 'OperationLog' 'lucky5_operation_log' ([ordered]@{
        legacy_id='id'; legacy_user_id='userId'; operator='operator'; member='member'; action='action'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'Message' 'lucky5_message' ([ordered]@{
        legacy_id='id'; channel='channel'; member='member'; period='period'; content='content'; status='status';
        order_id='orderId'; external_id='externalId'; error='error'; command_type='commandType'; reply='reply';
        processed_at='processedAt'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'RebateRecord' 'lucky5_rebate_record' ([ordered]@{
        id='id'; member_id='memberId'; normal_bet='normalBet'; dragon_bet='dragonBet';
        normal_amount='normalAmount'; dragon_amount='dragonAmount'; total_amount='totalAmount'
    }) 'createdAt' 'createdAt'),
    (New-MigrationSpec 'ChimaRecord' 'lucky5_chima_record' ([ordered]@{
        id='id'; member_id='memberId'; fake_amount='fakeAmount'; total_win='totalWin'
    }) 'updatedAt' 'updatedAt')
)

if (-not (Test-Path -LiteralPath $OldCompose)) { throw "Old compose file not found: $OldCompose" }
if (-not (Test-Path -LiteralPath $CurrentCompose)) { throw "Current compose file not found: $CurrentCompose" }

if (-not $SkipSchema) {
    Write-Host 'Applying tenant-aware Lucky5 schema and menu migration...'
    [IO.File]::ReadAllText($SchemaFile, [Text.Encoding]::UTF8) |
        docker compose -f $CurrentCompose exec -T mysql mysql "-u$CurrentDatabaseUser" "-p$CurrentDatabasePassword" $CurrentDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Failed to apply Lucky5 schema.' }
}

$sql = [Collections.Generic.List[string]]::new()
$sql.Add('SET NAMES utf8mb4;')
$sql.Add('SET SESSION sql_mode = ''STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'';')
$sourceCounts = [ordered]@{}

foreach ($spec in $specs) {
    $query = "SELECT COALESCE(json_agg(t)::text, '[]') FROM (SELECT * FROM `"$($spec.Source)`") AS t;"
    $jsonOutput = $query | docker compose -f $OldCompose exec -T postgres psql -U $OldDatabaseUser -d $OldDatabase -At -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Failed to read PostgreSQL model $($spec.Source)." }
    $parsedRows = ($jsonOutput -join "`n") | ConvertFrom-Json
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($parsedRow in $parsedRows) { $rows.Add($parsedRow) }
    $sourceCounts[$spec.Target] = $rows.Count
    if ($rows.Count -eq 0) {
        Write-Host ("{0,-28} {1,6} rows" -f $spec.Source, 0)
        continue
    }

    $columns = @($spec.Columns.Keys)
    $quotedColumns = ($columns | ForEach-Object { "``$_``" }) -join ','
    for ($offset = 0; $offset -lt $rows.Count; $offset += 200) {
        $last = [Math]::Min($offset + 199, $rows.Count - 1)
        $valueRows = for ($index = $offset; $index -le $last; $index++) {
            $row = $rows[$index]
            $values = foreach ($column in $columns) {
                ConvertTo-SqlLiteral (Get-RowValue $row $spec.Columns[$column])
            }
            '(' + ($values -join ',') + ')'
        }
        $updates = ($columns | Where-Object { $_ -notin @('id','tenant_id','create_time') } |
            ForEach-Object { "``$_``=VALUES(``$_``)" }) -join ','
        $sql.Add("INSERT INTO ``$($spec.Target)`` ($quotedColumns) VALUES`n$($valueRows -join ",`n") ON DUPLICATE KEY UPDATE $updates;")
    }
    Write-Host ("{0,-28} {1,6} rows" -f $spec.Source, $rows.Count)
}

# The legacy Draw table can contain the placeholder 00000 even though Issue already has the real five-digit result.
# Reconcile only exact placeholder rows with a validated result from the same tenant/user/period.
$sql.Add(@"
UPDATE lucky5_draw d
JOIN lucky5_issue i ON i.tenant_id=d.tenant_id AND i.user_id=d.user_id
  AND i.period=d.period AND i.deleted=b'0'
SET d.result=CONCAT(SUBSTRING(i.result,1,1),',',SUBSTRING(i.result,2,1),',',SUBSTRING(i.result,3,1),',',
                    SUBSTRING(i.result,4,1),',',SUBSTRING(i.result,5,1)),
    d.big_small=IF((CAST(SUBSTRING(i.result,1,1) AS UNSIGNED)+CAST(SUBSTRING(i.result,2,1) AS UNSIGNED)
      +CAST(SUBSTRING(i.result,3,1) AS UNSIGNED)+CAST(SUBSTRING(i.result,4,1) AS UNSIGNED)
      +CAST(SUBSTRING(i.result,5,1) AS UNSIGNED))>=23,'大','小'),
    d.odd_even=IF(MOD((CAST(SUBSTRING(i.result,1,1) AS UNSIGNED)+CAST(SUBSTRING(i.result,2,1) AS UNSIGNED)
      +CAST(SUBSTRING(i.result,3,1) AS UNSIGNED)+CAST(SUBSTRING(i.result,4,1) AS UNSIGNED)
      +CAST(SUBSTRING(i.result,5,1) AS UNSIGNED)),2)=1,'单','双'),
    d.dragon_tiger=IF(CAST(SUBSTRING(i.result,1,1) AS UNSIGNED)>CAST(SUBSTRING(i.result,5,1) AS UNSIGNED),'龙',
      IF(CAST(SUBSTRING(i.result,1,1) AS UNSIGNED)<CAST(SUBSTRING(i.result,5,1) AS UNSIGNED),'虎','和'))
WHERE d.tenant_id=$TenantId AND d.user_id=$OwnerUserId AND d.deleted=b'0'
  AND REPLACE(d.result,',','')='00000' AND i.result REGEXP '^[0-9]{5}$';
"@)

Write-Host 'Writing migrated rows to MySQL...'
($sql -join "`n") | docker compose -f $CurrentCompose exec -T mysql mysql "-u$CurrentDatabaseUser" "-p$CurrentDatabasePassword" $CurrentDatabase
if ($LASTEXITCODE -ne 0) { throw 'MySQL data migration failed.' }

Write-Host "Migration verification for tenant $TenantId"
foreach ($entry in $sourceCounts.GetEnumerator()) {
    $countSql = "SELECT COUNT(*) FROM ``$($entry.Key)`` WHERE tenant_id=$TenantId AND deleted=b'0';"
    $targetCount = (& docker compose -f $CurrentCompose exec -T mysql mysql "-u$CurrentDatabaseUser" "-p$CurrentDatabasePassword" -N $CurrentDatabase -e $countSql | Select-Object -Last 1).Trim()
    if ([long]$targetCount -lt [long]$entry.Value) {
        throw "Verification failed for $($entry.Key): source=$($entry.Value), target=$targetCount"
    }
    Write-Host ("{0,-30} source={1,6} target={2,6}" -f $entry.Key, $entry.Value, $targetCount)
}

Write-Host 'Lucky5 data migration completed successfully.'

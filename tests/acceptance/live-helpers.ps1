function Sql([string]$query){
 $res=$query | docker exec -i meterbill-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B'
 if($LASTEXITCODE -ne 0){throw 'SQL failed'}
 return $res
}
function Check($name,$ok,$evidence){
 $s=if($ok){'PASS'}else{'FAIL'}
 $checks.Add([pscustomobject]@{name=$name;status=$s;evidence=$evidence})
 Write-Host "$s $name : $evidence"
 $checks|ConvertTo-Json -Depth 15|Set-Content "$out/checks.json"
}
function Api($name,$method,$path,$headers=@{},$body=$null,$expected=@(200),$key=''){
 $h=@{}; foreach($k in $headers.Keys){$h[$k]=$headers[$k]}; if($key){$h['Idempotency-Key']=$key}
 $args=@{Uri="$BaseUrl$path";Method=$method;Headers=$h;SkipHttpErrorCheck=$true;TimeoutSec=30}
 if($null -ne $body){$args.ContentType='application/json';$args.Body=$body|ConvertTo-Json -Depth 15 -Compress}
 $w=Invoke-WebRequest @args; $j=$null;try{$j=$w.Content|ConvertFrom-Json}catch{}
 $redacted=if($path -match '/login$|/api-keys$'){'[credential response omitted]'}else{$w.Content}
 $http.Add([pscustomobject]@{name=$name;method=$method;path=$path;status=[int]$w.StatusCode;body=$redacted})
 $http|ConvertTo-Json -Depth 20|Set-Content "$out/http.json"
 Check $name ($expected -contains [int]$w.StatusCode) "HTTP $($w.StatusCode), code=$($j.code)"
 if($w.StatusCode -ge 400 -and $expected -notcontains [int]$w.StatusCode){throw "$name failed: $redacted"}
 return $j.data
}
function WaitEvent($eventId){
 for($i=0;$i -lt 40;$i++){
  $s=Sql "SELECT status FROM mb_usage_event WHERE id=$eventId;"
  if($s -eq 'PROCESSED'){return};Start-Sleep -Milliseconds 500
 };throw "Event $eventId not processed: $s"
}
function Event($id,$qty,$at,$subId=$sub.id){return @{subscriptionId=$subId;sourceId=$run;eventId=$id;meterCode='INPUT_TOKENS';modelCode='ACCEPTANCE_MODEL';quantity=$qty;occurredAt=$at}}


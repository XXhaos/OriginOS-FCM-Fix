param(
    [Parameter(Mandatory = $true)][string]$Instance,
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string]$CapturedFile,
    [string]$CurrentBootId,
    [string]$TargetPackage
)

# Read-only metadata; optional target summary exposes only the requested package/UID/install time.
# Exposes only structural status and historical caller UID/PID/start ticks for closure checks.
# Never exports signer digests, raw authority, boot IDs, consent IDs or diagnostic content.
# CapturedFile is an optional LOCAL binary shell CONTROL envelope for offline inspection.
# CurrentBootId is optional: without it callerBootMatchesCurrent is unknown, never inferred.
$ErrorActionPreference = 'Stop'
$taskUuid = '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
if ($Instance -cnotmatch $taskUuid) { throw 'Instance must be a canonical UUID v4' }
if ($CurrentBootId -and $CurrentBootId -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') { throw 'Invalid current boot identifier' }
if ($TargetPackage -and ($TargetPackage.Length -gt 255 -or $TargetPackage -cnotmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')) { throw 'Invalid target package' }
$taskMaximum = 2 * 1024 * 1024 + 44

function Get-ControlEnvelope {
    if ($CapturedFile) {
        $taskFile = Get-Item -LiteralPath $CapturedFile
        if ($taskFile.PSIsContainer -or $taskFile.Length -lt 84 -or $taskFile.Length -gt $taskMaximum) { throw 'Invalid capture size' }
        $taskBytes = [IO.File]::ReadAllBytes($taskFile.FullName)
        if ($taskBytes.Length -ne $taskFile.Length) { throw 'Capture changed during read' }
        return ,$taskBytes
    }
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB not found' }
    $taskInfo = [Diagnostics.ProcessStartInfo]::new()
    $taskInfo.FileName = $Adb
    # Only this fixed shell-owned path is accepted. No app startup, run-as, shell script or write.
    $taskInfo.Arguments = "exec-out cat /data/local/tmp/originos-fcm-guardian/$Instance/control.bin"
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskProcess = [Diagnostics.Process]::Start($taskInfo)
    $taskBuffer = [IO.MemoryStream]::new()
    $taskClock = [Diagnostics.Stopwatch]::StartNew()
    try {
        $taskErrorDrain = $taskProcess.StandardError.ReadToEndAsync()
        $taskChunk = [byte[]]::new(8192)
        while ($true) {
            $taskRemaining = 15000 - [int]$taskClock.ElapsedMilliseconds
            if ($taskRemaining -le 0) { throw 'CONTROL read timed out' }
            $taskRead = $taskProcess.StandardOutput.BaseStream.ReadAsync($taskChunk, 0, $taskChunk.Length)
            if (-not $taskRead.Wait($taskRemaining)) { throw 'CONTROL read timed out' }
            $taskCount = $taskRead.Result
            if ($taskCount -eq 0) { break }
            if ($taskBuffer.Length + $taskCount -gt $taskMaximum) { throw 'CONTROL exceeds size bound' }
            $taskBuffer.Write($taskChunk, 0, $taskCount)
        }
        $taskRemaining = [Math]::Max(1, 15000 - [int]$taskClock.ElapsedMilliseconds)
        if (-not $taskProcess.WaitForExit($taskRemaining) -or $taskProcess.ExitCode -ne 0) { throw 'CONTROL unavailable' }
        if ($taskBuffer.Length -lt 84) { throw 'CONTROL incomplete' }
        return ,$taskBuffer.ToArray()
    } finally {
        if (-not $taskProcess.HasExited) { $taskProcess.Kill() }
        $taskBuffer.Dispose(); $taskProcess.Dispose()
    }
}

if (-not ('GuardianDaemonMetadataV2' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

public static class GuardianDaemonMetadataV2 {
    const int MaxRoot = 2 * 1024 * 1024;
    static readonly string[] Roles = { "AUTHORITY", "AUTHORITY_INITIALIZED", "AUTHORITY_STOP_PENDING", "SELECTION",
        "STOPPED_JOURNAL", "STOPPED_BLOCKED", "RECOVERY_COUNTS", "COUNTS_UNAVAILABLE", "FCM_PROTECTION",
        "FCM_PROTECTION_HALT", "FCM_RECOVERY", "FCM_CONSUMED", "FCM_MCS_CONSUMED", "FCM_RECOVERY_HALT",
        "FCM_EPISODES", "HEARTBEAT", "HEARTBEAT_INITIALIZED", "HEARTBEAT_HALT", "HEARTBEAT_PENDING",
        "LEGACY_BUDGET", "LEGACY_BUDGET_INITIALIZED", "INTERRUPTED_ARTIFACTS", "HEARTBEAT_DAEMON_OWNER",
        "HEARTBEAT_DERIVATION", "CONTROL_PROPOSAL" };
    static readonly int[] Limits = {262144,1,1,262144,8192,1,1048576,1,512,1,32768,32768,32768,1,1048576,
        1024,1,1,256,262144,1,262144,1024,2048,614400};
    static void Need(bool value) { if (!value) throw new InvalidDataException("Invalid daemon metadata structure"); }
    static Dictionary<string,object> Map(params object[] pairs) {
        var value = new Dictionary<string,object>();
        for (int i=0;i<pairs.Length;i+=2) value.Add((string)pairs[i],pairs[i+1]);
        return value;
    }
    static string Sha(byte[] bytes) { using(var hash=SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant(); }
    static byte[] Checked(byte[] bytes, int maximum) {
        Need(bytes != null && bytes.Length >= 32 && bytes.Length <= maximum);
        var body = new byte[bytes.Length-32]; Array.Copy(bytes,body,body.Length);
        var digest = new byte[32]; Array.Copy(bytes,body.Length,digest,0,32);
        Need(Sha(body)==BitConverter.ToString(digest).Replace("-", "").ToLowerInvariant());
        return body;
    }
    sealed class R : IDisposable {
        readonly MemoryStream stream; readonly BinaryReader reader;
        public R(byte[] bytes) { stream=new MemoryStream(bytes,false); reader=new BinaryReader(stream); }
        public int Left { get {return checked((int)(stream.Length-stream.Position));} }
        public byte Byte() { return reader.ReadByte(); }
        public int Int() { uint v=0; for(int i=0;i<4;i++) v=(v<<8)|Byte(); return unchecked((int)v); }
        public long Long() { ulong v=0; for(int i=0;i<8;i++) v=(v<<8)|Byte(); return unchecked((long)v); }
        public bool Flag() { int v=Byte(); Need(v<=1); return v==1; }
        public byte[] Bytes(int n) { Need(n>=0 && n<=Left); var v=reader.ReadBytes(n); Need(v.Length==n); return v; }
        public string Text() { int n=(Byte()<<8)|Byte(); Need(n<=8192); return new UTF8Encoding(false,true).GetString(Bytes(n)); }
        public string SizedText(int maximum) {int n=Int();Need(n>=1&&n<=maximum);return new UTF8Encoding(false,true).GetString(Bytes(n));}
        public void End() { Need(Left==0); }
        public void Dispose() { reader.Dispose(); stream.Dispose(); }
    }
    sealed class Control { public string Instance,Digest,Migration; public long Epoch,Revision; public int Mode; }
    static Control Record(R r) {
        var c=new Control {Instance=r.Text(),Epoch=r.Long(),Revision=r.Long(),Digest=r.Text(),Mode=r.Int(),Migration=r.Text()};
        Need(c.Epoch>0 && c.Revision>0 && c.Mode>=0 && c.Mode<=2 && c.Digest.Length==64 && c.Migration.Length>0 && c.Migration.Length<=128);
        return c;
    }
    static void Request(R r) {
        Need(r.Text().Length>0); Need(r.Text().Length>0); Need(r.Long()>0 && r.Long()>0);
        int kind=r.Int(); Need(kind>=0 && kind<=2);
        if(kind==0) {r.Text();r.Text();} else {r.Text();Record(r);}
    }
    static string Ordinal(R r,string names,bool nullable=false) {
        int value=r.Int(); var all=names.Split(',');
        if(nullable && value==-1) return null;
        Need(value>=0 && value<all.Length); return all[value];
    }
    static string Name(R r,string names,bool nullable=false) {
        string value=r.Text(); if(nullable && value.Length==0) return null;
        Need(Array.IndexOf(names.Split(','),value)>=0); return value;
    }
    static object Inspect(Func<object> parse) {
        try {return parse();} catch {return Map("validation","INVALID");}
    }
    sealed class Identity {
        public string Package, Canonical; public int Uid; public long Installed;
    }
    static List<Identity> Identities(string text) {
        Need(Encoding.UTF8.GetByteCount(text)<=262144);
        var result=new List<Identity>();var packages=new HashSet<string>();
        if(String.IsNullOrWhiteSpace(text))return result;
        foreach(string line in text.Split('\n')) {
            var f=line.Split('|');Need(f.Length==4);int uid=0;long installed=0;
            Need(f[0].Length<=255&&Regex.IsMatch(f[0],@"\A[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+\z")&&
                Int32.TryParse(f[1],out uid)&&uid>=10000&&uid<100000&&Int64.TryParse(f[2],out installed)&&installed>0);
            var signers=f[3].Split(',');Need(signers.Length>=1&&signers.Length<=16);
            var seen=new HashSet<string>();foreach(string signer in signers)Need(Regex.IsMatch(signer,@"\A[0-9a-f]{64}\z")&&seen.Add(signer));
            Array.Sort(signers,StringComparer.Ordinal);Need(packages.Add(f[0]));
            result.Add(new Identity {Package=f[0],Uid=uid,Installed=installed,
                Canonical=f[0]+"|"+uid+"|"+installed+"|"+String.Join(",",signers)});
        }
        return result;
    }
    static Identity OneIdentity(string text) {var all=Identities(text);Need(all.Count==1);return all[0];}
    static object Selection(byte[] bytes,string target,out Identity selected) {
        selected=null;if(bytes==null)return null;
        foreach(var identity in Identities(new UTF8Encoding(false,true).GetString(bytes)))
            if(identity.Package==target)selected=identity;
        return Map("selected",selected!=null,"uid",selected==null?(object)null:selected.Uid,
            "firstInstallTime",selected==null?(object)null:selected.Installed);
    }
    static object Authority(byte[] bytes,string target,Identity selected) {
        if(bytes==null)return null;
        using(var r=new R(Checked(bytes,262144))) {
            Need(r.Int()==0x46434D41);int schema=r.Int();Need(schema>=1&&schema<=3&&(schema!=1||bytes.Length<=8192));
            long generation=r.Long();int mode=r.Byte(),reason=r.Int();Need(generation>0&&mode>=0&&mode<=(schema==3?3:2)&&
                reason>=-1&&reason<=5&&((mode==2)==(reason>=0)));
            int count=schema==1?(r.Flag()?1:0):r.Int();Need(count>=0&&count<=bytes.Length/100);
            var ids=new HashSet<string>();string lastPackage=null,targetId=null;Identity targetIdentity=null;int targetPolicy=0,targetKind=0;
            for(int i=0;i<count;i++) {
                int kind=schema==1?0:r.Byte();int tokenSchema=r.Int(),policy=r.Int(),user=r.Int();
                Need(kind>=0&&kind<=1&&tokenSchema==1&&user==0&&(kind==0?(policy>=1&&policy<=2):policy==4));
                string id=r.SizedText(36);long minted=r.Long(),time=r.Long();
                Need(Regex.IsMatch(id,@"\A[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\z")&&minted>0&&minted<=generation&&time>0&&
                    (schema!=1||minted==generation));
                string package=r.SizedText(256);int uid=r.Int();long installed=r.Long();int signers=r.Int();Need(signers>=1&&signers<=16);
                var signerValues=new string[signers];string lastSigner=null;
                for(int j=0;j<signers;j++){signerValues[j]=r.SizedText(64);Need(lastSigner==null||StringComparer.Ordinal.Compare(lastSigner,signerValues[j])<0);lastSigner=signerValues[j];}
                var identity=OneIdentity(package+"|"+uid+"|"+installed+"|"+String.Join(",",signerValues));
                r.SizedText(2048); // Provenance only; never printed or used to claim current readiness.
                Need((kind!=0||package=="org.telegram.messenger")&&(lastPackage==null||StringComparer.Ordinal.Compare(lastPackage,package)<0));
                if(schema==3)Need(ids.Add(id));else ids.Add(id);lastPackage=package;
                if(package==target){targetId=id;targetIdentity=identity;targetPolicy=policy;targetKind=kind;}
            }
            bool invalidated=false;long master=0;
            if(schema==3) {
                master=r.Long();Need(master>=0);int n=r.Int();Need(n>=0&&n<=count);string previous=null;
                for(int i=0;i<n;i++){string id=r.SizedText(36);Need(ids.Contains(id)&&(previous==null||StringComparer.Ordinal.Compare(previous,id)<0));previous=id;if(id==targetId)invalidated=true;}
            }
            int budget=r.Int();Need(budget>=1&&budget<=256);r.Bytes(budget);r.End();
            Need(schema==3?(mode!=0||count==0):(mode==1)==(count>0));Need(schema!=3||mode!=1||master>0);
            bool present=targetIdentity!=null;
            return Map("validation","STRUCTURE_AND_CHECKSUM_PASS","budgetValidation","BOUNDED_OPAQUE_ONLY","schema",schema,"mode",new[]{"DISABLED","ACTIVE","SUSPENDED","USER_PAUSED"}[mode],
                "targetConsentPresent",present,"policyVersion",present?(object)targetPolicy:null,
                "kind",present?(targetKind==0?"LEGACY":"RECIPIENT"):null,"invalidated",present?(object)invalidated:null,
                "exactSelectedIdentityMatch",selected==null||!present?(object)null:targetIdentity.Canonical==selected.Canonical,
                "targetRunnablePolicyPresent",present&&!invalidated&&mode==1&&(targetKind==1?targetPolicy==4:targetPolicy==2));
        }
    }
    static object Counts(byte[] bytes,string target,Identity selected) {
        if(bytes==null)return null;
        using(var r=new R(bytes)) {
            int schema=r.Int();Need(schema>=1&&schema<=2);
            long since=r.Long(),high=r.Long(),observed=r.Long();bool uncertain=r.Flag();int count=r.Int();
            Need(since>0&&high>=0&&observed>=since&&count>=0&&count<=512);
            int matching=0,exact=0;long previous=0;
            for(int i=0;i<count;i++) {
                long request=r.Long();var identity=OneIdentity(r.Text());long at=r.Long();
                Need(request>previous&&request<=high&&at>0&&at<=observed);previous=request;
                if(identity.Package==target)matching++;
                if(selected!=null&&identity.Canonical==selected.Canonical)exact++;
            }
            bool truncated=false,migrated=false;int days=0,matchingDays=0,exactDays=0;long matchingCount=0,exactCount=0;
            if(schema==2) {
                string zone=r.Text();truncated=r.Flag();migrated=r.Flag();days=r.Int();Need(days>=0&&days<=4096&&(zone.Length>0||days==0));
                var keys=new HashSet<string>();
                for(int i=0;i<days;i++) {
                    var identity=OneIdentity(r.Text());long day=r.Long();int n=r.Int();long first=r.Long(),last=r.Long();
                    Need(n>0&&first>0&&last>=first&&last<=observed&&keys.Add(identity.Canonical+"|"+day));
                    if(identity.Package==target){matchingDays++;matchingCount=checked(matchingCount+n);}
                    if(selected!=null&&identity.Canonical==selected.Canonical){exactDays++;exactCount=checked(exactCount+n);}
                }
            }
            r.End();return Map("schema",schema,"receiptCount",count,"requestHighWater",high,"clockUncertain",uncertain,
                "matchingPackageReceiptCount",matching,"matchingSelectedIdentityReceiptCount",selected==null?(object)null:exact,
                "dayBucketCount",days,"matchingPackageDayBuckets",matchingDays,"matchingPackageDailyRecoveryCount",matchingCount,
                "matchingSelectedIdentityDayBuckets",selected==null?(object)null:exactDays,
                "matchingSelectedIdentityDailyRecoveryCount",selected==null?(object)null:exactCount,
                "historyTruncated",truncated,"migratedFromReceipts",migrated);
        }
    }
    static object Journal(byte[] bytes,string target) {
        if(bytes==null)return null;
        using(var r=new R(bytes)) {
            int schema=r.Int();Need(schema>=1&&schema<=3);long high=r.Long();Need(high>=0);
            object latest=null;bool present=r.Flag(),matching=false;
            if(present) {
                long request=r.Long();var identity=OneIdentity(r.Text());long created=r.Long(),baseline=r.Long();r.Text();int user=r.Int();
                bool stopped=r.Flag(),manual=r.Flag(),risk=r.Flag();
                string stage=Name(r,"PREPARED,CLAIMED,FINISHED,RECONCILED");
                string outcome=Name(r,"FLAG_CLEARED,STILL_STOPPED,NO_WRITE_NEEDED,BLOCKED,UNCONFIRMED",true);r.Text();
                string source="MANUAL";if(schema>=2){source=Name(r,"MANUAL,AUTOMATIC");r.Text();r.Long();r.Int();}
                Need(request>0&&created>0&&baseline>0&&user==0&&stopped&&risk&&outcome!="UNCONFIRMED"&&
                    ((stage=="PREPARED"||stage=="CLAIMED")== (outcome==null)));
                matching=identity.Package==target;
                if(matching)latest=Map("package",target,"requestId",request,"stage",stage,"outcome",outcome,"source",source);
            }
            r.End();return Map("schema",schema,"claimedHighWater",high,"latestPresent",present,"latestMatchesTarget",matching,"latest",latest);
        }
    }
    static object Parent(byte[] bytes) {
        if(bytes==null) return null;
        using(var r=new R(Checked(bytes,512))) {
            Need(r.Int()==0x46435031 && r.Int()==1); bool configured=r.Flag();
            r.Text();r.Text();r.Text();bool prepared=r.Flag();r.End();
            return Map("configured",configured,"recoveryPrepared",prepared);
        }
    }
    static object Heartbeat(byte[] bytes,string currentBoot) {
        if(bytes==null) return null;
        using(var r=new R(Checked(bytes,1024))) {
            Need(r.Int()==0x474D5348);int schema=r.Int();Need(schema>=1 && schema<=3);
            long generation=r.Long();Need(generation>0);bool configured=r.Flag();
            string pause=Ordinal(r,"RECONFIRM,HOST_LOST,UNKNOWN_RESULT,LEFTOVER_PENDING,OFF_PENDING,STORAGE_FAILURE,WAITING_HOST",true);
            bool pending=r.Text().Length>0;r.Text();bool session=r.Text().Length>0;
            string status=Ordinal(r,"NOT_SENT,REQUESTED,UNKNOWN",true);
            string reason=Ordinal(r,"INVALID_REQUEST,SHELL_UNAVAILABLE,WRONG_USER,IDENTITY_UNKNOWN,IDENTITY_CHANGED,SHARED_UID,OBSERVATION_UNKNOWN,REPORTED_DISCONNECTED,STALE_OBSERVATION,BUSY,TIMED_OUT,CANCELLED,BRIDGE_UNAVAILABLE,COMMAND_ACCEPTED,COMMAND_FAILED,OUTPUT_INCOMPLETE,MALFORMED_RESPONSE,SESSION_REVOKED,INTERVAL_NOT_ELAPSED,DUPLICATE_ATTEMPT,CALLER_UNAVAILABLE,EXECUTION_NOT_READY",true);
            bool durable=false;int? consentVersion=null;string block=null;bool caller=false;object bootEqual=null;bool arm=false;
            int? callerUid=null,callerPid=null;long? callerStartTicks=null;
            if(schema>=2) {
                durable=r.Flag();if(durable){consentVersion=r.Int();Need(consentVersion>=1 && consentVersion<=3);r.Text();Need(r.Int()==0);r.Text();}
                block=Ordinal(r,"COLD_RECONSTRUCTION,UNKNOWN_RESULT,LEFTOVER_PENDING,IDENTITY_CHANGED,SECURITY_FAILURE,STORAGE_FAILURE,UNCERTAIN_OWNER",true);r.Text();
            }
            if(schema==3) {
                caller=r.Flag();if(caller){callerUid=r.Int();callerPid=r.Int();callerStartTicks=r.Long();string boot=r.Text();if(!String.IsNullOrEmpty(currentBoot))bootEqual=boot==currentBoot;}
                arm=r.Text().Length>0;
            }
            r.End();return Map("schema",schema,"generation",generation,"configured",configured,"pause",pause,
                "sessionPresent",session,"pendingAttempt",pending,"pendingArm",arm,"resumeBlock",block,"lastStatus",status,
                "lastReason",reason,"durableConsent",durable,"consentVersion",consentVersion,"callerPresent",caller,"callerBootMatchesCurrent",bootEqual,
                "callerUid",callerUid,"callerPid",callerPid,"callerStartTicks",callerStartTicks);
        }
    }
    static object Pending(byte[] bytes) {
        if(bytes==null)return null;
        using(var r=new R(Checked(bytes,256))) {
            Need(r.Int()==0x474D5350 && r.Int()==1);string kind=Ordinal(r,"ARM,HEARTBEAT");r.Text();long generation=r.Long();r.Text();r.End();
            return Map("kind",kind,"generation",generation);
        }
    }
    static object Recovery(byte[] bytes) {
        if(bytes==null)return null;
        using(var r=new R(Checked(bytes,32768))) {
            Need(r.Int()==0x46435231);int schema=r.Int();Need(schema>=1 && schema<=3);
            bool configured=r.Flag();r.Text();bool consumed=r.Flag();r.Text();r.Long();r.Long();if(schema>=2)r.Long();
            int count=r.Int();Need(count>=0 && count<=64);var tail=new List<object>();
            for(int i=0;i<count;i++) {
                long wall=r.Long(),elapsed=r.Long();Need(wall>0 && elapsed>=0);
                string action=Name(r,"OBSERVATION,CONSENT_ON,CONSENT_OFF,HOST_ENDED,PRIVILEGE_LOST,INELIGIBLE,ATTEMPT_CONSUMED,REQUEST_RESULT,READBACK,STORAGE_FAILURE,POWER_SAMPLE");
                string service=Name(r,"REPORTED_CONNECTED,REPORTED_DISCONNECTED,UNKNOWN,SERVICE_ABSENT");
                string completeness=Name(r,"COMPLETE,PARTIAL,DENIED,UNAVAILABLE,MALFORMED,TRUNCATED,TIMED_OUT,CANCELLED");
                string socket=Name(r,"OBSERVED,NO_MATCH,UNKNOWN");bool eligible=r.Flag();
                string status=Name(r,"NOT_SENT,REQUESTED,UNKNOWN",true);
                string reason=Name(r,"COMMAND_ACCEPTED,NOT_ABSENT,IDENTITY_CHANGED,TARGET_UNQUALIFIED,AUTHORITY_LOST,BUSY,TIMED_OUT,CANCELLED,COMMAND_FAILED,BRIDGE_UNAVAILABLE,INVALID_REQUEST,NOT_DISCONNECTED",true);
                string eligibility=Name(r,"ELIGIBLE,CONSENT_OFF,HOST_UNAVAILABLE,SHELL_UNAVAILABLE,NETWORK_UNAVAILABLE");
                string unknown=null,operation=null;
                if(schema>=2) {
                    unknown=Name(r,"INVALID_REQUEST,SHELL_UNAVAILABLE,WRONG_USER,IDENTITY_UNKNOWN,IDENTITY_CHANGED,SHARED_UID,CAPABILITY_UNAVAILABLE,TIMED_OUT,CANCELLED,BUSY,BRIDGE_UNAVAILABLE,MALFORMED_RESPONSE",true);
                    if(r.Flag()) {r.Int();r.Text();r.Text();byte a=r.Byte(),b=r.Byte();Need((a<=1||a==255)&&(b<=1||b==255));r.Long();r.Long();r.Text();}
                    if(schema>=3)operation=Name(r,"SERVICE_ABSENT_SEND,PRESENT_DISCONNECTED_MCS",true);
                }
                if(i>=count-6)tail.Add(Map("atMillis",wall,"elapsedMillis",elapsed,"action",action,"service",service,
                    "completeness",completeness,"socket",socket,"eligible",eligible,"eligibility",eligibility,
                    "status",status,"reason",reason,"unknown",unknown,"operation",operation));
            }
            bool mcs=false;if(schema>=3){mcs=r.Flag();r.Text();r.Long();r.Long();}r.End();
            return Map("schema",schema,"configured",configured,"consumed",consumed,"mcsConsumed",mcs,"eventCount",count,"tail",tail);
        }
    }
    public static object Parse(byte[] envelope,string instance,string currentBoot,string targetPackage) {
        Need(String.IsNullOrEmpty(targetPackage)||(targetPackage.Length<=255&&Regex.IsMatch(targetPackage,@"\A[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+\z")));
        Need(envelope.Length>=84 && envelope.Length<=MaxRoot+44);byte[] root;
        using(var r=new R(Checked(envelope,MaxRoot+44))) {
            Need(r.Int()==0x47445331 && r.Int()==0);int size=r.Int();Need(size>=40&&size<=MaxRoot&&size==r.Left);root=r.Bytes(size);r.End();
        }
        using(var r=new R(Checked(root,MaxRoot))) {
            Need(r.Int()==0x47445231 && r.Int()==1);long dataRevision=r.Long();Need(dataRevision>0);var control=Record(r);Need(control.Instance==instance);
            bool halt=r.Flag();if(halt){Request(r);r.Long();}
            int receipts=r.Int();Need(receipts>=0&&receipts<=64);for(int i=0;i<receipts;i++){Request(r);Record(r);}
            Need(r.Int()==Roles.Length);var domains=new byte[Roles.Length][];var metadata=new List<object>();
            for(int i=0;i<Roles.Length;i++) {
                Need(r.Int()==i);int size=r.Int();Need(size>=-1&&size<=Limits[i]&&size<=r.Left);
                if(size>=0)domains[i]=r.Bytes(size);
                metadata.Add(Map("role",Roles[i],"present",size>=0,"bytes",size<0?0:size,"sha256",size<0?null:Sha(domains[i])));
            }
            r.End();Need(domains[0]!=null&&Sha(domains[0])==control.Digest);
            var result=Map("checksum","PASS","rootSha256",Sha(root),"mode",new[]{"PREPARED","ACTIVE","OFF"}[control.Mode],
                "revision",control.Revision,"dataRevision",dataRevision,"halt",halt,"receiptCount",receipts,"roles",metadata,
                "parent",Inspect(()=>Parent(domains[8])),"heartbeat",Inspect(()=>Heartbeat(domains[15],currentBoot)),
                "heartbeatPending",Inspect(()=>Pending(domains[18])),"recovery",Inspect(()=>Recovery(domains[10])),
                "scope","READ_ONLY_METADATA; not full authorization validation; requested target and historical Guardian process metadata only; no signers, boot IDs, consent IDs or content");
            if(!String.IsNullOrEmpty(targetPackage)) {
                Identity selected=null;
                object selection=Inspect(()=>Selection(domains[3],targetPackage,out selected));
                result.Add("target",Map("package",targetPackage,"selection",selection,
                    "countsUnavailable",domains[7]!=null,"stoppedBlocked",domains[5]!=null,
                    "counts",Inspect(()=>Counts(domains[6],targetPackage,selected)),
                    "journal",Inspect(()=>Journal(domains[4],targetPackage)),"authority",Inspect(()=>Authority(domains[0],targetPackage,selected))));
            }
            return result;
        }
    }
}
'@
}

$taskEnvelope = Get-ControlEnvelope
try {
    [GuardianDaemonMetadataV2]::Parse($taskEnvelope, $Instance, $CurrentBootId, $TargetPackage) | ConvertTo-Json -Depth 10 -Compress
} catch {
    # Never print exception messages from payload parsers or raw ADB stderr.
    throw 'Daemon metadata validation failed; no raw payload exported'
} finally { [Array]::Clear($taskEnvelope, 0, $taskEnvelope.Length) }

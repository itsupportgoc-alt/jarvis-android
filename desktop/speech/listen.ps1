# Continuous dictation through Windows' own speech recogniser (SAPI).
#
# Writes one line per recognised utterance:
#   READY              the microphone is open
#   FINAL <text>       what was heard
#   ERROR <message>    the recogniser could not start
#
# No audio ever leaves this machine: System.Speech recognises locally with the
# installed speech language pack. The parent process kills this script to stop
# listening.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

try {
    Add-Type -AssemblyName System.Speech
} catch {
    Write-Output "ERROR Windows speech is unavailable on this PC: $($_.Exception.Message)"
    exit 1
}

try {
    $engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine
} catch {
    Write-Output "ERROR Windows has no speech recogniser installed. Add a speech language under Settings, Time and language, Speech."
    exit 1
}

try {
    $engine.SetInputToDefaultAudioDevice()
} catch {
    Write-Output "ERROR No microphone is available. Connect one and check Windows sound input, then press MIC."
    exit 1
}

$engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
$engine.InitialSilenceTimeout = [TimeSpan]::FromSeconds(10)
$engine.EndSilenceTimeout = [TimeSpan]::FromMilliseconds(700)
$engine.EndSilenceTimeoutAmbiguous = [TimeSpan]::FromMilliseconds(900)
$engine.BabbleTimeout = [TimeSpan]::FromSeconds(0)

Write-Output 'READY'
[Console]::Out.Flush()

while ($true) {
    $result = $null
    try {
        $result = $engine.Recognize([TimeSpan]::FromSeconds(20))
    } catch [System.TimeoutException] {
        continue
    } catch {
        Write-Output "ERROR recogniser failed: $($_.Exception.Message)"
        [Console]::Out.Flush()
        Start-Sleep -Milliseconds 800
        continue
    }
    if ($null -ne $result) {
        $text = ($result.Text -replace '\s+', ' ').Trim()
        if ($text.Length -gt 0) {
            Write-Output ("FINAL " + $text)
            [Console]::Out.Flush()
        }
    }
}

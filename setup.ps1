$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force models | Out-Null

# OpenCV's own model repository mirrored on its Hugging Face account.
# SHA-256 hashes are pinned: never accept an HTML error response as an ONNX file.
$downloads = @(
    @{
        Name = 'face_detection_yunet_2023mar.onnx'
        Sha = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'
        Url = 'https://huggingface.co/opencv/opencv_zoo/resolve/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx'
    },
    @{
        Name = 'face_recognition_sface_2021dec.onnx'
        Sha = '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'
        Url = 'https://huggingface.co/opencv/opencv_zoo/resolve/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx'
    }
)

foreach ($item in $downloads) {
    $file = Join-Path 'models' $item.Name
    $valid = (Test-Path $file) -and ((Get-FileHash $file -Algorithm SHA256).Hash -ieq $item.Sha)
    if (-not $valid) {
        Write-Host "Downloading OpenCV model: $($item.Name) (SFace is about 39 MB)..."
        $temp = "$file.part"
        if (Test-Path $temp) { Remove-Item $temp -Force }
        try {
            Invoke-WebRequest -Uri $item.Url -OutFile $temp -UseBasicParsing -MaximumRedirection 10
            if ((Get-FileHash $temp -Algorithm SHA256).Hash -ine $item.Sha) {
                throw "Downloaded model failed SHA-256 verification; do not use it. URL: $($item.Url)"
            }
            Move-Item -Path $temp -Destination $file -Force
        } catch {
            if (Test-Path $temp) { Remove-Item $temp -Force }
            throw
        }
    } else {
        Write-Host "Model already verified: $($item.Name)"
    }
}

mvn package
if ($LASTEXITCODE -ne 0) { throw 'Build failed. Check Maven output.' }
Write-Host 'SFace/YuNet upgrade setup complete. Run start.ps1'

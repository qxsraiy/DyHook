# -*- coding: utf-8 -*-
<#
.SYNOPSIS
    一键把 C:\tools\dyhook-github 推送到 GitHub（自动建仓库）

.USAGE
    # 公开仓库
    .\push-to-github.ps1 -Token ghp_xxxxxxxxxxxx

    # 私有仓库 / 自定义名字
    .\push-to-github.ps1 -Token ghp_xxxx -RepoName DyHook -Private

.NOTES
    Token 需要 repo 权限（classic PAT 勾 repo；fine-grained 勾 Administration:Read and write + Contents:Read and write）
    获取: https://github.com/settings/tokens
#>
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$RepoName = "DyHook",
    [switch]$Private,
    [string]$Description = "抖音长文章文案提取 LSPosed 模块 (LibXposed API 102)"
)

$ErrorActionPreference = 'Stop'
$RepoDir = "C:\tools\dyhook-github"

if (-not (Test-Path $RepoDir)) { throw "找不到仓库目录: $RepoDir" }
Set-Location $RepoDir

# ---------- 1. 取 GitHub 用户名 ----------
Write-Host "[1/4] 校验 token..." -ForegroundColor Cyan
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}
$me = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $headers -Method Get
$owner = $me.login
Write-Host "      登录身份: $owner" -ForegroundColor Green

# ---------- 2. 建仓库（已存在则跳过） ----------
Write-Host "[2/4] 创建仓库 $owner/$RepoName ..." -ForegroundColor Cyan
$body = @{
    name        = $RepoName
    description = $Description
    private     = [bool]$Private
    auto_init   = $false
} | ConvertTo-Json

try {
    $repo = Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Headers $headers `
        -Method Post -Body $body -ContentType "application/json"
    Write-Host "      已创建: $($repo.html_url)" -ForegroundColor Green
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 422) {
        Write-Host "      仓库已存在，直接复用" -ForegroundColor Yellow
    } else {
        throw "创建仓库失败 ($code): $($_.Exception.Message)"
    }
}

# ---------- 3. 配置 remote ----------
Write-Host "[3/4] 配置 remote ..." -ForegroundColor Cyan
$remoteUrl = "https://$owner:$Token@github.com/$owner/$RepoName.git"
git remote remove origin 2>$null
git remote add origin $remoteUrl
Write-Host "      origin -> https://github.com/$owner/$RepoName.git" -ForegroundColor Green

# ---------- 4. 推送 ----------
Write-Host "[4/4] 推送 ..." -ForegroundColor Cyan
git branch -M main 2>$null
git push -u origin main

# 清理：把带 token 的 remote 换回干净地址，避免 token 留在 .git/config
git remote set-url origin "https://github.com/$owner/$RepoName.git"

Write-Host ""
Write-Host "完成! 仓库地址: https://github.com/$owner/$RepoName" -ForegroundColor Green
Write-Host "打 Release 上传 APK: https://github.com/$owner/$RepoName/releases/new" -ForegroundColor Gray

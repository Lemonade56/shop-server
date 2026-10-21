$BASE = "http://localhost:8080"

function Call-Api {
    param([string]$Method, [string]$Path, [string]$Body = "", [hashtable]$Headers = @{})
    $url = $BASE + $Path
    try {
        if ($Method -eq "GET") {
            $resp = Invoke-RestMethod -Uri $url -Method $Method -Headers $Headers
        } else {
            $resp = Invoke-RestMethod -Uri $url -Method $Method -Body $Body -ContentType "application/json" -Headers $Headers
        }
        $code = $resp.code
        Write-Host "  [$code] $Path"
        if ($resp.data -ne $null -and $code -eq 200) {
            $s = ($resp.data | ConvertTo-Json -Depth 4 -Compress)
            if ($s.Length -gt 250) { $s = $s.Substring(0,250) + "..." }
            Write-Host "         => $s"
        }
        return $resp
    } catch {
        Write-Host "  [ERR ] $Path   $($_.Exception.Message)"
        return $null
    }
}

Write-Host ""
Write-Host "=============================================="
Write-Host " ShopServer Full API Test"
Write-Host "=============================================="

Write-Host "`n--- 1. LOGIN ---"
$loginBody = @{phone="13917825125";password="123456"} | ConvertTo-Json
$loginResp = Call-Api "POST" "/app/user/login/password" $loginBody
$TOKEN = $null
if ($loginResp -and $loginResp.data -and $loginResp.data.token) {
    $TOKEN = $loginResp.data.token
    Write-Host "  TOKEN: $TOKEN"
} else {
    Write-Host "  Login failed."
    exit 1
}
$H = @{ Authorization = "Bearer $TOKEN" }

Write-Host "`n--- 2. PUBLIC ENDPOINTS ---"
Call-Api "GET"  "/health"
Call-Api "GET"  "/app/page/home"
Call-Api "GET"  "/app/recipe/list?page=1"
Call-Api "GET"  "/app/recipe/detail?id=1"
Call-Api "GET"  "/app/recipe/categories"
Call-Api "POST" "/app/foods/info/page" (@{page=1} | ConvertTo-Json)
Call-Api "POST" "/app/foods/search/keyword/list" "{}"

Write-Host "`n--- 3. USER PROFILE ---"
Call-Api "GET"  "/app/user/info/person" -Headers $H
Call-Api "GET"  "/app/order/info/userCount" -Headers $H
Call-Api "GET"  "/app/user/info/logoff" -Headers $H
# re-login
$loginResp = Call-Api "POST" "/app/user/login/password" $loginBody
$TOKEN = $loginResp.data.token; $H = @{ Authorization = "Bearer $TOKEN" }

Write-Host "`n--- 4. ADDRESS ---"
$addrBody = @{receiver="ZhangSan";phone="13917825125";province="Shanghai";city="Shanghai";district="Pudong";detail="Zhangjiang";isDefault=$true} | ConvertTo-Json
Call-Api "POST" "/app/user/address/add"    $addrBody -Headers $H
Call-Api "POST" "/app/user/address/list"   (@{page=1} | ConvertTo-Json) -Headers $H
Call-Api "GET"  "/app/user/address/default" -Headers $H

Write-Host "`n--- 5. GOODS COLLECTION ---"
Call-Api "POST" "/app/market/collect/add"    (@{goodsId=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/market/collect/isCollect" (@{goodsId=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/market/collect/page"   (@{page=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/market/collect/delete" (@{goodsId=1} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 6. RECIPE COLLECTION ---"
Call-Api "POST" "/app/recipe/collect/add"       (@{recipeId=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/recipe/collect/isCollect" (@{recipeId=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/recipe/collect/page"      (@{page=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/recipe/collect/delete"    (@{recipeId=1} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 7. CART ---"
Call-Api "POST" "/app/cart/add"    (@{goodsId=1;quantity=2} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/cart/add"    (@{goodsId=2;quantity=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/cart/list"   "{}" -Headers $H
Call-Api "POST" "/app/cart/update" (@{id=1;quantity=3} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/cart/select" (@{all=$true;isSelected=$true} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/cart/clearChecked" "{}" -Headers $H
Call-Api "POST" "/app/cart/list"   "{}" -Headers $H
Call-Api "POST" "/app/cart/delete" (@{ids=@(1)} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 8. COUPONS ---"
Call-Api "POST" "/app/market/coupon/info/page" (@{page=1} | ConvertTo-Json)
Call-Api "POST" "/app/market/coupon/user/page" (@{page=1} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 9. FEEDBACK ---"
Call-Api "POST" "/app/feedback/submit" (@{type="suggestion";content="hope to have one-click add"} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/feedback/page"   (@{page=1} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 10. NOTIFICATIONS ---"
Call-Api "POST" "/app/market/notice/page" (@{page=1} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 11. CUSTOMER SERVICE ---"
Call-Api "POST" "/app/cs/session/create" "{}" -Headers $H
Call-Api "POST" "/app/cs/msg/page"       (@{sessionId="test";page=1} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/cs/msg/send"       (@{sessionId="test";content="hello";type=0} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 12. OSS UPLOAD ---"
Call-Api "POST" "/app/base/comm/upload" "{}" -Headers $H

Write-Host "`n--- 13. DICT ---"
Call-Api "POST" "/app/dict/info/data" (@{type="order_cancel_reason"} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/dict/info/data" (@{type="order_pay_type"} | ConvertTo-Json) -Headers $H

Write-Host "`n--- 14. ORDER FLOW ---"
$addrList = Call-Api "POST" "/app/user/address/list" (@{page=1} | ConvertTo-Json) -Headers $H
$addressId = $null
if ($addrList.data.list -and $addrList.data.list.Count -gt 0) { $addressId = $addrList.data.list[0].id }

if ($addressId) {
    $createBody = @{addressId=$addressId;goodsList=@(@{goodsId=1;quantity=1});remark="test";from="product"} | ConvertTo-Json -Depth 5
    Call-Api "POST" "/app/order/info/create" $createBody -Headers $H
    Call-Api "POST" "/app/order/info/page" (@{page=1} | ConvertTo-Json) -Headers $H
} else {
    Write-Host "  SKIP: no addressId"
}

Write-Host "`n--- 15. COMMENT ---"
Call-Api "POST" "/app/foods/comment/submit" (@{goodsId=1;content="good";images=@()} | ConvertTo-Json) -Headers $H
Call-Api "POST" "/app/foods/comment/page"   (@{foodsId=1;page=1} | ConvertTo-Json)

Write-Host "`n=============================================="
Write-Host "  ALL TESTS DONE"
Write-Host "=============================================="

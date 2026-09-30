& .ai/scripts/loopctl.ps1 'dispatch-transition' '.ai/state/20260930-review-fixes.json' -DispatchId 'DISPATCH-20260930-REVIEW-TEST_PASS-b93b6e' -DispatchStatus 'acked'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .ai/scripts/loopctl.ps1 'dispatch-transition' '.ai/state/20260930-review-fixes.json' -DispatchId 'DISPATCH-20260930-REVIEW-TEST_PASS-b93b6e' -DispatchStatus 'running'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& .ai/scripts/loopctl.ps1 'evaluate-direct' '.ai/state/20260930-team-recycle.json' -ProposalPath '.ai/docs/20260930-team-recycle/direct-KNOWLEDGE.json' -Actor 'workflow-manager'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

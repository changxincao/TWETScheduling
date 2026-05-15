# PiecewiseLinearFunction merge/find contract test report

Scope: findMinimal and mergeMinimum under [a,T] forward-frontier input contract.

- PASS: findMinimal: normal multi-segment case
- PASS: findMinimal: vertical jump left-limit case
- PASS: findMinimal: left/right position selection on continuous and discontinuous endpoints
- PASS: mergeMinimum: [a,T] same-right-bound contract cases
- PASS: random contract frontier sweep: mergeMinimum on 500 [a,T] cases

Summary: passed=5, warnings=0, failed=0

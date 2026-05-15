# PiecewiseLinearFunction property test report

Scope: shiftX, add, prefix/suffix minimization, findMinimal, dominates, mergeMinimum, updateDominatedIntervals.
Reference idea: route-evaluation operations in Ibaraki et al. style piecewise-linear time-penalty functions.

- PASS: shiftX: value preservation on shifted domain
- PASS: add: pointwise sum on overlapping domain
- PASS: setDomain(fillOutsideWithBigM): keeps right bound and fills outside window
- PASS: minimizePrefixInPlace: normal continuous/nonconvex case
- PASS: minimizeSuffixInPlace: normal continuous/nonconvex case
- PASS: minimizePrefixInPlace: boundary real-minimum regression
- PASS: minimizeSuffixInPlace: boundary real-minimum regression
- PASS: findMinimal: normal multi-segment case
- PASS: findMinimal: vertical jump left-limit case
- PASS: findMinimal: left/right position selection on continuous and discontinuous endpoints
- FAIL: dominates returns true when dominator does not cover dominated function domain. shorter=[0,5], longer=[0,10]. If dominance is defined over the full dominated domain, this is unsafe.
- PASS: mergeMinimum: overlapping domains under forward frontier semantics
- FAIL: mergeMinimum disjoint-left domain throws exception. NullPointerException: Cannot read field "end" because "<local9>" is null
- FAIL: mergeMinimum disjoint-right domain equals prefix-min lower envelope. expected=10.0, actual=1.0
- PASS: updateDominatedIntervals: full domination
- PASS: updateDominatedIntervals: partial middle domination no exception
- PASS: updateDominatedIntervals: complex forward-closure cases
- WARN: prefix/suffix minimization is not pure mathematical min when curUpperBound is below function values. curUpperBound=100, original=200, prefix result at t=2 is 100.0. This is intentional pruning behavior but unsafe for exact pricing with negative dual offsets.
- PASS: random sweep: add and mergeMinimum forward-frontier semantics on 500 continuous cases
- PASS: random frontier sweep: mergeMinimum on 500 prefix-minimized cases

Summary: passed=16, warnings=1, failed=3

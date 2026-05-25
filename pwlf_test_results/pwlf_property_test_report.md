# PiecewiseLinearFunction property test report

Scope: shiftX, add, prefix/suffix minimization, findMinimal, dominates, mergeMinimum, updateDominatedIntervals.
Reference idea: route-evaluation operations in Ibaraki et al. style piecewise-linear time-penalty functions.

- PASS: shiftX: value preservation on shifted domain
- PASS: add: pointwise sum on overlapping domain
- PASS: setDomain(fillOutsideWithBigM): keeps right bound and fills outside window
- PASS: setDomain(fillOutsideWithBigM): endpoint and forward/backward closure semantics
- PASS: setDomain(fillOutsideWithBigM): add keeps [a,T] contract
- PASS: minimizePrefixInPlace: normal continuous/nonconvex case
- PASS: minimizeSuffixInPlace: normal continuous/nonconvex case
- PASS: minimizePrefixInPlace: boundary real-minimum regression
- PASS: minimizeSuffixInPlace: boundary real-minimum regression
- PASS: evaluate: internal breakpoint takes min of adjacent limits
- PASS: normalize(Direction): forward keeps T, backward keeps 0
- PASS: normalize(Direction): random forward/backward sweep on 500 cases
- PASS: findMinimal: normal multi-segment case
- PASS: findMinimal: vertical jump left-limit case
- PASS: findMinimal: left/right position selection on continuous and discontinuous endpoints
- PASS: dominates: rejects insufficient domain coverage
- PASS: mergeMinimum: overlapping domains under forward frontier semantics
- FAIL: mergeMinimum disjoint-left domain throws exception. IllegalArgumentException: mergeMinimum requires positive overlap: this=[5.0,8.0], g=[0.0,3.0]
- FAIL: mergeMinimum disjoint-right domain throws exception. IllegalArgumentException: mergeMinimum requires positive overlap: this=[5.0,8.0], g=[10.0,12.0]
- PASS: updateDominatedIntervals: full domination
- PASS: updateDominatedIntervals: partial middle domination no exception
- PASS: updateDominatedIntervals: complex forward-closure cases
- PASS: mergeMinimum(Direction): random forward/backward sweep on 500 cases
- PASS: updateDominatedIntervals(Direction): random forward/backward sweep on 500 cases
- WARN: prefix/suffix minimization is not pure mathematical min when curUpperBound is below function values. curUpperBound=100, original=200, prefix result at t=2 is 100.0. This is intentional pruning behavior but unsafe for exact pricing with negative dual offsets.
- FAIL: random sweep operation failure. case=296, IllegalArgumentException: mergeMinimum requires positive overlap: this=[0.07541349536248654,2.6991738189542662], g=[2.715680941018819,8.58913605297042], f=[0.075,2.099,-2.181,-1.034][2.099,2.699,-0.205,-5.182], g=[2.716,3.538,-2.724,6.867][3.538,4.869,-1.843,3.748][4.869,8.589,0.237,-6.378]
- WARN: random sweep found failures. failureCount=1 / 500
- PASS: random frontier sweep: mergeMinimum on 500 prefix-minimized cases

Summary: passed=23, warnings=2, failed=3

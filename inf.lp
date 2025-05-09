\ENCODING=ISO-8859-1
\Problem name: .\model.lp_conflict

Minimize
 obj1:
Subject To
 timeFlow_1_5#20: 648 x_1_5#9 + C_1#34 - C_5#46 <= 555
 timeFlow_2_4#23: 648 x_2_4#13 + C_2#37 - C_4#43 <= 516
 timeFlow_3_2#26: 648 x_3_2#17 - C_2#37 + C_3#40 <= 504
 timeFlow_4_1#29: 648 x_4_1#21 - C_1#34 + C_4#43 <= 312
 timeFlow_5_3#35: 648 x_5_3#28 - C_3#40 + C_5#46 <= 393
 c49#48:          x_5_3#28  = 1
 c50#49:          x_3_2#17  = 1
 c51#50:          x_2_4#13  = 1
 c52#51:          x_4_1#21  = 1
\Sum of equality rows in the conflict:
\ sum_eq: x_4_1#21 + x_3_2#17 + x_5_3#28 + x_2_4#13  = 4
Bounds
 0 <= x_4_1#21 <= 1
 0 <= x_3_2#17 <= 1
 0 <= x_5_3#28 <= 1
 0 <= x_2_4#13 <= 1
 0 <= x_1_5#9 <= 1
      C_1#34 Free
      C_2#37 Free
      C_3#40 Free
      C_4#43 Free
      C_5#46 Free
Binaries
 x_4_1#21  x_3_2#17  x_5_3#28  x_2_4#13  x_1_5#9 
End

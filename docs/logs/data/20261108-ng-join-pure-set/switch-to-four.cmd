@echo off
setlocal
chcp 65001 >nul
cd /d D:\ccx_work\考虑交付的机器调度\work1\experiments\20261108-ng-join-pure-set-83861aeb
if /i not "%CD%"=="D:\ccx_work\考虑交付的机器调度\work1\experiments\20261108-ng-join-pure-set-83861aeb" exit /b 18
if exist interrupted-2parallel exit /b 17
if exist runs\join-n060-set02-m3-family-high-wwide-f3-set\ngDssr\join-n060-set02-m3-family-high-wwide-f3-set.core-summary.csv exit /b 16
if exist runs\join-n060-set02-m3-family-high-wwide-f3-or\ngDssr\join-n060-set02-m3-family-high-wwide-f3-or.core-summary.csv exit /b 16
mkdir interrupted-2parallel
if errorlevel 1 exit /b 15
move runs\join-n060-set02-m3-family-high-wwide-f3-set interrupted-2parallel\join-n060-set02-m3-family-high-wwide-f3-set
if errorlevel 1 exit /b 14
move runs\join-n060-set02-m3-family-high-wwide-f3-or interrupted-2parallel\join-n060-set02-m3-family-high-wwide-f3-or
if errorlevel 1 exit /b 14
move worker.lock interrupted-2parallel\worker.lock
if errorlevel 1 exit /b 14
move scheduler-logs interrupted-2parallel\scheduler-logs
if errorlevel 1 exit /b 14
for %%F in (worker.cmd receipt.json README.md asset-hashes.tsv experiment.status) do copy /b "%%F" "interrupted-2parallel\%%F"
exit /b 0

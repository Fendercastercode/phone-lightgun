@echo off
rem Double-click to start the RetroSight PC host.
cd /d "%~dp0host"
if not exist ".venv\Scripts\python.exe" (
  echo First run: setting up Python environment...
  python -m venv .venv || (echo Python 3.10+ is required: https://www.python.org/downloads/ & pause & exit /b 1)
  .venv\Scripts\python -m pip install -r requirements.txt || (pause & exit /b 1)
)
.venv\Scripts\python -m lightgun_host %*
if errorlevel 1 pause

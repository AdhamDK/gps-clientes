@echo off
cd /d C:\Users\Usuario\Documents\GPS_CLIENTES
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8000 1> uvicorn_run.log 2> uvicorn_run_err.log
echo EXITCODE=%ERRORLEVEL% >> uvicorn_run_err.log
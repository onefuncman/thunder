@echo off
echo Downloading
curl https://game.havenandhearth.com/res/%1.res -o ./resources/custom/res/%1.res --create-dirs -k
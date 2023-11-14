#!/bin/bash

case "$1" in
    start)
        # open a port on the firewall
        firewall-cmd --permanent --zone=public --add-port=${3}/udp > /dev/null 2>&1
        firewall-cmd --reload > /dev/null 2>&1

        # 'vbmcd' Retrieve the PID of a process
        pid=$(pgrep -a vbmcd)

        # 'vbmcd' Run commands only when no process is running
        if [ -z "$pid" ]; then
            # run vbmc daemon
            vbmcd > /dev/null 2>&1
        fi

        vbmc add ${2} --port ${3} --username ablecloud --password Ablecloud1!
        vbmc start ${2}
        ;;
    delete)
        vbmc delete ${2}
        # close a port on the firewall
        firewall-cmd --permanent --zone=public --remove-port=${3}/udp > /dev/null 2>&1
        firewall-cmd --reload > /dev/null 2>&1
        ;;
    *)
        echo "ERROR"
        ;;
esac
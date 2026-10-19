. Exercita formato 4, BASE, indireção, indexação e shift.
ADDRS   START 1000
COUNT   EQU 8
FIRST   +LDB #FAR
        BASE FAR
        LDA FAR
        SHIFTL A,COUNT
        STA RESULT
        LDX #1
        LDCH TEXT,X
        LDA @PTR
STOP    J STOP
PTR     WORD FAR
TEXT    BYTE C'Ab;C'
RESULT  RESW 1
        RESB 4096
FAR     WORD 1
        NOBASE
        END FIRST

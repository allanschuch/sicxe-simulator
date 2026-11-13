. Macros: chamada aninhada (INIT chama CLRREG) e definição aninhada (MKADD define ADDK).
. A = 3 + 10 + 4 = 17 depois de cinco instruções; o resultado fica em RESULT.
MACROS  START 0

CLRREG  MACRO &R
        CLEAR &R
        MEND

SAVE    MACRO &REG,&MEM
        ST&REG &MEM          ; STA, STX, STL...
        MEND

INIT    MACRO &VAL
        CLRREG X             ; chamada de macro dentro de macro
        LDA #&VAL
        MEND

MKADD   MACRO &K
ADDK    MACRO &N             ; macro definida dentro de macro
        ADD #&K
        ADD #&N
        MEND
        MEND

FIRST   INIT 3
        MKADD 10             ; define ADDK com &K = 10
        ADDK 4
        SAVE A,RESULT
STOP    J STOP
RESULT  RESW 1
        END FIRST

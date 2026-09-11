# Balance Local

Aplicativo Android nativo em Kotlin + Jetpack Compose para leitura local de uma balança BLE Chipsea.

## Recursos

- Sem anúncios.
- Sem login.
- Sem rastreadores.
- Scanner BLE.
- Leitura de Manufacturer Data.
- Parser experimental de pacote de 17 bytes.
- Tela de diagnóstico com pacote bruto.
- Cálculo inicial de IMC e TMB.
- Estrutura pronta para histórico com Room.

## Abrir

1. Instale o Android Studio.
2. Abra a pasta `BalanceLocal`.
3. Aguarde o Gradle sincronizar.
4. Conecte um aparelho Android físico.
5. Ative Bluetooth e conceda as permissões.
6. Execute o app.

## Observação sobre o protocolo

A estrutura usada é:

`10 FF C0 CC WW WW II II SS EE KK MAC(6)`

O peso foi inicialmente interpretado como Big Endian e dividido por 100. Porém, o pacote de teste informado:

`10 FF C0 D7 01 27 17 70 0A 01 24 78 66 A5 5C 22 91`

resulta em 2,95 kg nesse modelo. Portanto, a posição/escala do peso deve ser confirmada com novos pacotes e pesos conhecidos.

## Próximas etapas

- Validar checksum real.
- Capturar vários pacotes em pesos conhecidos.
- Confirmar se o MAC no advertising está invertido ou espelhado.
- Implementar histórico Room.
- Adicionar perfil com sexo, idade e altura.
- Implementar FFMI somente quando houver gordura corporal confiável.

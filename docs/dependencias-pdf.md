# Dependencias para documentos PDF

Fecha de comprobación: 7 de noviembre de 2026.

## Biblioteca

Apache PDFBox 3.0.8, fijado explícitamente en backend/pom.xml. Se usa para crear PDF y, en pruebas, reabrirlos, extraer texto e inspeccionar páginas/fuentes.

Se eligió una biblioteca Java para mantener la generación dentro del backend Docker existente, sin navegador de servidor, servicio adicional ni ejecución de Python en producción. El proyecto publica PDFBox bajo Apache License 2.0. Sus avisos de distribución se conservan en las dependencias empaquetadas.

Fuentes primarias: [sitio y licencia de PDFBox](https://pdfbox.apache.org/) y [dependencia Maven oficial](https://pdfbox.apache.org/3.0/getting-started.html).

## Fuente tipográfica

DejaVu Sans 2.37, archivo incluido en backend/src/main/resources/fonts/DejaVuSans.ttf. Se incrusta como subconjunto por documento; no requiere fuentes del sistema operativo.

Se conserva el aviso original extraído de los metadatos de la fuente en LICENSE-DejaVu.txt, con copyright y condiciones Bitstream Vera/Arev, licencia y huella del archivo. Las modificaciones de DejaVu figuran como dominio público en ese aviso. La fuente permanece sin modificaciones.

SHA-256: 7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954.

## Operación

Los PDF se componen en memoria y se entregan como adjuntos privados; no se guardan en el volumen MySQL ni se crean entidades adicionales. El tamaño funcional se acota por los límites existentes de diagnóstico/trabajo y cien renglones por presupuesto.

Las futuras actualizaciones de PDFBox o fuente deben repetir las pruebas de permisos, texto, precisión, límites de página y renderizado. La revisión visual usa Poppler fuera de la aplicación; no es una dependencia de producción.

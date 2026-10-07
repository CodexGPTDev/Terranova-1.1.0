# Publicar el proyecto en GitHub

El ZIP de fuentes contiene la raíz del proyecto Terranova. Publica su contenido completo: README, LICENSE, CHANGELOG, documentación, `src/`, `build.gradle`, `settings.gradle`, scripts y carpeta `gradle/`. El `.gitignore` excluye resultados de compilación y datos privados del servidor.

## Desde la web

1. Crea un repositorio público llamado `Terranova` en tu cuenta. Puedes dejarlo vacío porque este proyecto ya contiene README y licencia.
2. Descomprime el ZIP de fuentes y entra en la carpeta Terranova.
3. En el repositorio usa **Add file → Upload files** y sube el contenido de esa carpeta.
4. Comprueba que `README.md` aparece en la raíz y que también se han incluido `.github/`, `.gitignore` y el wrapper. Si tu explorador oculta esos archivos, utiliza Git para subirlos.
5. En **Releases → Draft a new release**, crea el tag `v1.1.0`, título `Terranova 1.1.0` y adjunta `Terranova-1.1.0.jar`. Usa las novedades de CHANGELOG como descripción.

## Con Git

Dentro de la carpeta del proyecto, sustituye TU_USUARIO por tu usuario de GitHub:

```bash
git init
git add .
git commit -m "Terranova 1.1.0: ciudades, núcleos y devolución de objetos"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/Terranova.git
git push -u origin main
```

En Linux conserva el permiso ejecutable de `gradlew` con `chmod +x gradlew`. El workflow también aplica ese permiso antes de compilar.

El workflow de `.github/workflows/build.yml` instala Java 25, ejecuta `./gradlew build` y guarda el JAR y reportes como artifacts de la ejecución. Se activa en push, pull request o ejecución manual. La release descargable se publica mediante los pasos anteriores.

El código original incluye la licencia MIT. Los plugins opcionales y las dependencias externas conservan sus licencias y no son parte del código original de Terranova. No subas la carpeta de datos real, credenciales, bases de datos o `item-secret.key` a GitHub.

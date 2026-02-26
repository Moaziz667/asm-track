import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app  = await NestFactory.create(AppModule);
  const port = parseInt(process.env.PORT ?? '3200', 10);
  await app.listen(port);
  console.log(`Shopify Query service listening on http://localhost:${port}`);
}
bootstrap();

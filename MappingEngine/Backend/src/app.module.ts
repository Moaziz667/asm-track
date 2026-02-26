import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ServeStaticModule } from '@nestjs/serve-static';
import { join } from 'path';
import { mkdirSync } from 'fs';
import { MappingModule } from './mapping/mapping.module';
import { MappingConfig } from './mapping/mapping.entity';

// Ensure the data directory exists (important inside Docker volume mount)
const dataDir = process.env.DATA_DIR ?? join(__dirname, '..', 'data');
mkdirSync(dataDir, { recursive: true });

@Module({
  imports: [
    // ── SQLite database ────────────────────────────────────────────────────
    // DATA_DIR env var is set in docker-compose; falls back to <project>/data
    TypeOrmModule.forRoot({
      type: 'better-sqlite3',
      database: join(dataDir, 'mapping-engine.sqlite'),
      entities: [MappingConfig],
      synchronize: true,
      logging: false,
    }),

    // ── Serve the Frontend admin panel at / ───────────────────────────────
    // STATIC_ROOT env var is set in docker-compose; falls back to adjacent Frontend folder
    ServeStaticModule.forRoot({
      rootPath: process.env.STATIC_ROOT ?? join(__dirname, '..', '..', 'Frontend'),
      serveRoot: '/',
      exclude: ['/api/(.*)'],
    }),

    // ── Feature modules ───────────────────────────────────────────────────
    MappingModule,
  ],
})
export class AppModule {}

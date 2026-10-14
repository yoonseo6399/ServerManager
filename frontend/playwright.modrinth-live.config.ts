import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./modrinth-live-tests',workers:1,timeout:90000,use:{baseURL:'https://localhost:8796',ignoreHTTPSErrors:true,viewport:{width:1440,height:1000}},reporter:'list'});

import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 6,
};

export default function () {
  http.post('http://localhost:8080/orders/buy/14');
  sleep(0.3);
}
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

/** Builds the AxiosError the client sees when the backend answers with the unified error body. */
export function makeApiError(status: number, errorCode: string, url = '/api/test'): AxiosError {
  const config: InternalAxiosRequestConfig = { url, headers: new AxiosHeaders() }
  const response: AxiosResponse = {
    status,
    statusText: `HTTP ${status}`,
    headers: {},
    config,
    data: { error: errorCode, message: `HTTP ${status}` },
  }
  return new AxiosError(`HTTP ${status}`, undefined, config, undefined, response)
}
